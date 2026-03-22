package com.mezze.language.formatter

import com.mezze.language.ast.*
import com.mezze.language.parser.OperatorMapping

/**
 * Pretty printer for Mezze AST.
 *
 * Converts AST nodes back to formatted source code with consistent style.
 * Handles comment attachment based on source positions.
 */
class PrettyPrinter {
    private val config = FormatterConfig
    private val output = StringBuilder()
    private var indentLevel = 0
    private var currentLine = 1
    private var currentColumn = 0
    private var comments: List<Comment> = emptyList()
    private var nextCommentIndex = 0

    // Reverse mapping: method name -> operator symbol (for new desugaring: x.add {r = y})
    private val methodToOperator: Map<String, String> = buildMap {
        for ((op, binaryOp) in OperatorMapping.BINARY_OPERATORS) {
            put(binaryOp.method, op)
        }
    }

    // Reverse mapping: method name -> operator symbol (for unary: x.neg {})
    private val methodToUnaryOperator: Map<String, String> = buildMap {
        for ((op, unaryOp) in OperatorMapping.UNARY_OPERATORS) {
            put(unaryOp.method, op)
        }
    }

    /**
     * Print a complete program to formatted source code.
     */
    fun print(program: Program): String {
        output.clear()
        indentLevel = 0
        currentLine = 1
        currentColumn = 0
        comments = program.comments.sortedBy { it.line * 10000 + it.column }
        nextCommentIndex = 0

        printProgram(program)

        // Print any remaining trailing comments
        printRemainingComments()

        return output.toString().trimEnd() + "\n"
    }

    // =========================================================================
    // PROGRAM STRUCTURE
    // =========================================================================

    private fun printProgram(program: Program) {
        var isFirst = true
        var lastWasUse = false

        for (item in program.items) {
            // Print leading comments for this item
            printLeadingComments(getItemLine(item))

            val isUse = item is UseDeclaration || item is PubUseDeclaration

            // Add blank line between top-level items (but not before first)
            if (!isFirst) {
                // Use statements are grouped together without blank lines
                // But add a blank line after the last use statement before other code
                if (lastWasUse && !isUse) {
                    // Transition from use block to user code - add blank line
                    newLine()
                } else if (!lastWasUse) {
                    // Between non-use items, add blank line
                    newLine()
                }
                // Between consecutive use statements - no blank line (just the newline from printing)
            }
            isFirst = false
            lastWasUse = isUse

            printProgramItem(item)
        }
    }

    private fun getItemLine(item: ProgramItem): Int {
        return when (item) {
            is LetBinding -> item.expression.sourceLocation?.line ?: currentLine
            is TypeDeclaration -> item.sourceLocation?.line ?: currentLine
            is EffectDeclaration -> item.sourceLocation?.line ?: currentLine
            is AbilityDeclaration -> item.sourceLocation?.line ?: currentLine
            is ImplDeclaration -> item.sourceLocation?.line ?: currentLine
            is ImplAbilityDeclaration -> item.sourceLocation?.line ?: currentLine
            is UseDeclaration -> item.sourceLocation?.line ?: currentLine
            is PubUseDeclaration -> item.sourceLocation?.line ?: currentLine
            is TopLevelExpression -> item.expression.sourceLocation?.line ?: currentLine
        }
    }

    private fun printProgramItem(item: ProgramItem) {
        when (item) {
            is LetBinding -> printLetBinding(item)
            is TypeDeclaration -> printTypeDeclaration(item)
            is EffectDeclaration -> printEffectDeclaration(item)
            is AbilityDeclaration -> printAbilityDeclaration(item)
            is ImplDeclaration -> printImplDeclaration(item)
            is ImplAbilityDeclaration -> printImplAbilityDeclaration(item)
            is UseDeclaration -> printUseDeclaration(item)
            is PubUseDeclaration -> printPubUseDeclaration(item)
            is TopLevelExpression -> {
                printExpression(item.expression)
                newLine()
            }
        }
    }

    // =========================================================================
    // LET BINDINGS
    // =========================================================================

    private fun printLetBinding(binding: LetBinding) {
        if (binding.visibility == Visibility.PUBLIC) {
            append("pub ")
        }
        append("let ")
        printPattern(binding.pattern)

        binding.typeAnnotation?.let {
            append(": ")
            printTypeAnnotation(it)
        }

        append(" = ")
        printExpression(binding.expression)
        newLine()
    }

    // =========================================================================
    // TYPE DECLARATIONS
    // =========================================================================

    private fun printTypeDeclaration(decl: TypeDeclaration) {
        if (decl.visibility == Visibility.PUBLIC) {
            append("pub ")
        }

        when (decl.definition) {
            is NewtypeDefinition -> {
                append("newtype ")
                append(decl.name)
                printTypeParams(decl.typeParams)
                append(" = ")
                val newtypeDef = decl.definition as NewtypeDefinition
                append(newtypeDef.constructor)
                append(" ")
                printTypeAnnotation(newtypeDef.wrappedType)
            }
            else -> {
                append("type ")
                append(decl.name)
                printTypeParams(decl.typeParams)
                append(" = ")
                printTypeDefinition(decl.definition)
            }
        }
        newLine()
    }

    private fun printTypeParams(typeParams: List<String>) {
        if (typeParams.isNotEmpty()) {
            append(" ")
            // Type params already include the ' prefix from the parser
            append(typeParams.joinToString(" "))
        }
    }

    private fun printTypeDefinition(def: TypeDefinition) {
        when (def) {
            is RecordDefinition -> printRecordDefinition(def)
            is VariantDefinition -> printVariantDefinition(def)
            is NewtypeDefinition -> {
                // Handled in printTypeDeclaration
            }
        }
    }

    private fun printRecordDefinition(def: RecordDefinition) {
        if (def.fields.isEmpty()) {
            append("{}")
            return
        }

        val singleLine = calculateRecordDefWidth(def) <= config.RECORD_SINGLE_LINE_THRESHOLD
        if (singleLine) {
            append("{ ")
            def.fields.forEachIndexed { index, field ->
                append(field.name)
                append(": ")
                printTypeAnnotation(field.type)
                if (index < def.fields.size - 1) {
                    append(", ")
                }
            }
            if (def.isOpen) {
                append(", ...")
            }
            append(" }")
        } else {
            append("{")
            newLine()
            indent {
                def.fields.forEachIndexed { index, field ->
                    printIndent()
                    append(field.name)
                    append(": ")
                    printTypeAnnotation(field.type)
                    if (config.TRAILING_COMMA_IN_MULTILINE || index < def.fields.size - 1) {
                        append(",")
                    }
                    newLine()
                }
                if (def.isOpen) {
                    printIndent()
                    append("...")
                    newLine()
                }
            }
            printIndent()
            append("}")
        }
    }

    private fun printVariantDefinition(def: VariantDefinition) {
        // Mezze variant syntax: | Constructor1, Constructor2 { data: Type }, ... |
        // Calculate total width to decide single-line vs multi-line
        val estimatedWidth = 4 + def.constructors.sumOf { c ->
            c.name.length + (c.dataType?.let { 1 + estimateTypeWidth(it) } ?: 0) + 2
        }
        val simple = estimatedWidth <= config.RECORD_SINGLE_LINE_THRESHOLD

        if (simple && def.constructors.isNotEmpty()) {
            // Single line: | Constructor1, Constructor2 |
            append("| ")
            def.constructors.forEachIndexed { index, constructor ->
                if (index > 0) append(", ")
                append(constructor.name)
                constructor.dataType?.let {
                    append(" ")
                    printTypeAnnotation(it)
                }
            }
            if (def.isOpen) {
                append(", ...")
            }
            append(" |")
        } else {
            // Multi-line format - use same | ... | syntax but spread across lines
            append("|")
            newLine()
            indent {
                def.constructors.forEachIndexed { index, constructor ->
                    printIndent()
                    append(constructor.name)
                    constructor.dataType?.let {
                        append(" ")
                        printTypeAnnotation(it)
                    }
                    append(",")
                    newLine()
                }
                if (def.isOpen) {
                    printIndent()
                    append("...")
                    newLine()
                }
            }
            printIndent()
            append("|")
        }
    }

    // =========================================================================
    // EFFECT AND ABILITY DECLARATIONS
    // =========================================================================

    private fun printEffectDeclaration(decl: EffectDeclaration) {
        append("effect ")
        append(decl.name)

        if (decl.associatedTypes.isNotEmpty()) {
            append(" having ")
            append(decl.associatedTypes.joinToString(", "))
        }

        append(" where {")
        newLine()
        indent {
            decl.operations.forEachIndexed { index, op ->
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
                // Add comma after each operation except the last
                if (index < decl.operations.size - 1) {
                    append(",")
                }
                newLine()
            }
        }
        printIndent()
        append("}")
        newLine()
    }

    private fun printAbilityDeclaration(decl: AbilityDeclaration) {
        append("ability ")
        append(decl.name)
        // Generic type params go before 'for', receiver goes after 'for'
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

        if (decl.dependencies.isNotEmpty()) {
            append(" with ")
            decl.dependencies.forEachIndexed { index, dep ->
                if (index > 0) append(", ")
                append(dep.abilityName)
                if (dep.typeArgs.isNotEmpty()) {
                    append(" ")
                    dep.typeArgs.forEachIndexed { i, arg ->
                        if (i > 0) append(" ")
                        printTypeAnnotation(arg)
                    }
                }
            }
        }

        append(" where {")
        newLine()
        indent {
            decl.operations.forEachIndexed { index, op ->
                printIndent()
                append(op.name)
                append(": ")
                printTypeAnnotation(op.paramType)
                append(" -> ")
                printTypeAnnotation(op.returnType)
                // Add comma after each operation except the last
                if (index < decl.operations.size - 1) {
                    append(",")
                }
                newLine()
            }
        }
        printIndent()
        append("}")
        newLine()
    }

    private fun printImplDeclaration(decl: ImplDeclaration) {
        append("impl ")
        if (decl.effect.qualifier != null) {
            append(decl.effect.qualifier!!.fullPath)
            append("::")
        }
        append(decl.effect.localName)
        append(" for ")
        append(decl.performerName)

        if (decl.performerParams.isNotEmpty()) {
            append(" { ")
            decl.performerParams.forEachIndexed { index, param ->
                if (index > 0) append(", ")
                append(param.name)
                append(": ")
                printTypeAnnotation(param.type)
            }
            append(" }")
        }

        if (decl.associatedTypeBindings.isNotEmpty()) {
            append(" having ")
            decl.associatedTypeBindings.entries.forEachIndexed { index, (name, type) ->
                if (index > 0) append(", ")
                // name already includes the ' prefix from the parser
                append("$name = ")
                printTypeAnnotation(type)
            }
        }

        // impl bodies always go multi-line with where { on its own conceptual line
        append(" where {")
        newLine()
        indent {
            decl.operations.forEachIndexed { index, op ->
                printIndent()
                append(op.name)
                append(" = ")
                printExpression(op.implementation)
                // Trailing comma like records: always between items, plus trailing if multiple
                if (decl.operations.size > 1 || index < decl.operations.size - 1) {
                    append(",")
                }
                newLine()
            }
        }
        printIndent()
        append("}")
        newLine()
    }

    private fun printImplAbilityDeclaration(decl: ImplAbilityDeclaration) {
        append("impl ")

        if (decl.forallVars.isNotEmpty()) {
            append("forall ")
            append(decl.forallVars.joinToString(" ") { "'$it" })
            append(". ")
        }

        if (decl.isInherent) {
            // Inherent impl: impl Type where { ... }
            decl.targetTypes.forEachIndexed { index, type ->
                if (index > 0) append(" ")
                printTypeAnnotation(type)
            }
        } else {
            val abilityRef = decl.abilityRef
            if (abilityRef?.qualifier != null) {
                append(abilityRef.qualifier!!.fullPath)
                append("::")
            }
            append(decl.abilityName!!)
            append(" for ")
            decl.targetTypes.forEachIndexed { index, type ->
                if (index > 0) append(" ")
                printTypeAnnotation(type)
            }
        }

        if (decl.associatedTypeBindings.isNotEmpty()) {
            append(" having ")
            decl.associatedTypeBindings.entries.forEachIndexed { index, (name, type) ->
                if (index > 0) append(", ")
                // name already includes the ' prefix from the parser
                append("$name = ")
                printTypeAnnotation(type)
            }
        }

        // impl bodies always go multi-line with where { on its own conceptual line
        append(" where {")
        newLine()
        indent {
            decl.operations.forEachIndexed { index, op ->
                printIndent()
                append(op.name)
                append(" = ")
                printExpression(op.implementation)
                // Trailing comma like records: always between items, plus trailing if multiple
                if (decl.operations.size > 1 || index < decl.operations.size - 1) {
                    append(",")
                }
                newLine()
            }
        }
        printIndent()
        append("}")
        newLine()
    }

    // =========================================================================
    // USE DECLARATIONS
    // =========================================================================

    private fun printUseDeclaration(decl: UseDeclaration) {
        append("use ")
        printUsePath(decl.path)
        decl.alias?.let {
            append(" as ")
            append(it)
        }
        newLine()
    }

    private fun printPubUseDeclaration(decl: PubUseDeclaration) {
        append("pub use ")
        printUsePath(decl.path)
        decl.alias?.let {
            append(" as ")
            append(it)
        }
        newLine()
    }

    private fun printUsePath(path: UsePath) {
        when (path) {
            is ModulePath -> append(path.segments.joinToString("::"))
            is SelectivePath -> {
                append(path.module.joinToString("::"))
                append("::{ ")
                path.items.forEachIndexed { index, item ->
                    if (index > 0) append(", ")
                    append(item.name)
                    item.alias?.let {
                        append(" as ")
                        append(it)
                    }
                }
                append(" }")
            }
        }
    }

    // =========================================================================
    // EXPRESSIONS
    // =========================================================================

    private fun printExpression(expr: Expression) {
        when (expr) {
            is IntLiteral -> append(expr.value.toString())
            is NatLiteral -> append("${expr.value}n")
            is FloatLiteral -> append(formatFloat(expr.value))
            is FractionLiteral -> append("${expr.numerator}//${expr.denominator}")
            is StringLiteral -> printStringLiteral(expr)
            is Identifier -> printIdentifier(expr)
            is RecordLiteral -> printRecordLiteral(expr)
            is SeqLiteral -> printSeqLiteral(expr)
            is MemberAccess -> printMemberAccess(expr)
            is VariantConstruction -> printVariantConstruction(expr)
            is LambdaExpression -> printLambdaExpression(expr)
            is FunctionCall -> printFunctionCall(expr)
            is IfExpression -> printIfExpression(expr)
            is MatchExpression -> printMatchExpression(expr)
            is DoExpression -> printDoExpression(expr)
            is PerformExpression -> printPerformExpression(expr)
            is EffectCallExpression -> printEffectCallExpression(expr)
            is PipePlaceholder -> append("@")
            is PipeExpression -> printPipeExpression(expr)
            is ParenthesizedExpression -> {
                append("(")
                printExpression(expr.expression)
                append(")")
            }
            is StateMachineExpression -> {
                // State machines are compiler-generated, should not appear in user code
                append("<state-machine>")
            }
        }
    }

    private fun printStringLiteral(expr: StringLiteral) {
        if (expr.isInterpolated && expr.parts != null) {
            append("\"")
            for (part in expr.parts) {
                when (part) {
                    is StringPart -> append(escapeString(part.value))
                    is ExpressionPart -> {
                        append("\$(")
                        printExpression(part.expression)
                        append(")")
                    }
                }
            }
            append("\"")
        } else {
            append("\"")
            append(escapeString(expr.value))
            append("\"")
        }
    }

    private fun printIdentifier(expr: Identifier) {
        if (expr.isQualified) {
            append(expr.toBindingKey().toKeyString())
        } else {
            append(expr.localName)
        }
    }

    private fun printRecordLiteral(expr: RecordLiteral) {
        if (expr.entries.isEmpty() && !expr.isOpen) {
            append("{}")
            return
        }

        val singleLine = calculateRecordLiteralWidth(expr) <= config.RECORD_SINGLE_LINE_THRESHOLD
        if (singleLine) {
            append("{ ")
            var first = true
            for (entry in expr.entries) {
                if (!first) append(", ")
                first = false
                printRecordLiteralEntry(entry)
            }
            if (expr.isOpen) {
                if (!first) append(", ")
                append("...")
            }
            append(" }")
        } else {
            append("{")
            newLine()
            indent {
                for ((index, entry) in expr.entries.withIndex()) {
                    printIndent()
                    printRecordLiteralEntry(entry)
                    if (config.TRAILING_COMMA_IN_MULTILINE || index < expr.entries.size - 1 || expr.isOpen) {
                        append(",")
                    }
                    newLine()
                }
                if (expr.isOpen) {
                    printIndent()
                    append("...")
                    newLine()
                }
            }
            printIndent()
            append("}")
        }
    }

    private fun printRecordLiteralEntry(entry: RecordLiteralEntry) {
        when (entry) {
            is RecordFieldEntry -> {
                append(entry.name)
                entry.value?.let {
                    append(" = ")
                    printExpression(it)
                }
            }
            is RecordSpreadEntry -> {
                append("...")
                printExpression(entry.expression)
            }
        }
    }

    private fun printSeqLiteral(expr: SeqLiteral) {
        if (expr.elements.isEmpty()) {
            append("[]")
            return
        }

        val singleLine = calculateSeqLiteralWidth(expr) <= config.ARRAY_SINGLE_LINE_THRESHOLD
        if (singleLine) {
            append("[")
            expr.elements.forEachIndexed { index, element ->
                if (index > 0) append(", ")
                printSeqElement(element)
            }
            append("]")
        } else {
            append("[")
            newLine()
            indent {
                expr.elements.forEachIndexed { index, element ->
                    printIndent()
                    printSeqElement(element)
                    if (config.TRAILING_COMMA_IN_MULTILINE || index < expr.elements.size - 1) {
                        append(",")
                    }
                    newLine()
                }
            }
            printIndent()
            append("]")
        }
    }

    private fun printSeqElement(element: SeqElement) {
        when (element) {
            is SeqRegularElement -> printExpression(element.expression)
            is SeqSpreadElement -> {
                append("...")
                printExpression(element.expression)
            }
        }
    }

    private fun printMemberAccess(expr: MemberAccess) {
        printExpression(expr.receiver)
        append(".")
        append(expr.member)
    }

    private fun printVariantConstruction(expr: VariantConstruction) {
        append(expr.constructor)
        expr.value?.let {
            append(" ")
            // Wrap in parens if it's a complex expression
            val needsParens = it is FunctionCall || it is IfExpression || it is MatchExpression
            if (needsParens) append("(")
            printExpression(it)
            if (needsParens) append(")")
        }
    }

    private fun printLambdaExpression(expr: LambdaExpression) {
        val startColumn = currentColumn
        printLambdaParameters(expr.parameters)

        if (expr.abilityConstraints.isNotEmpty()) {
            append(" with ")
            expr.abilityConstraints.forEachIndexed { index, constraint ->
                if (index > 0) append(", ")
                append(constraint.abilityName)
                if (constraint.typeArgs.isNotEmpty()) {
                    append(" ")
                    constraint.typeArgs.forEachIndexed { i, arg ->
                        if (i > 0) append(" ")
                        printTypeAnnotation(arg)
                    }
                }
            }
        }

        expr.returnType?.let {
            append(" -> ")
            printTypeAnnotation(it)
        }

        // Estimate if the body would fit on this line
        val estimatedBodyWidth = estimateExprWidth(expr.body)
        val wouldExceedLine = currentColumn + 4 + estimatedBodyWidth > config.MAX_LINE_LENGTH

        if (wouldExceedLine && !isSimpleExpression(expr.body)) {
            // Break after -> and indent body
            append(" ->")
            newLine()
            indent {
                printIndent()
                printExpression(expr.body)
            }
        } else {
            append(" -> ")
            printExpression(expr.body)
        }
    }

    /**
     * Print a pipe expression with nice formatting.
     *
     * Short chains stay on one line:
     *   x |> f { a = @ } |> g { b = @ }
     *
     * Long chains break to multiple lines with |> at the start of each line:
     *   someValue
     *       |> firstFunction { arg = @ }
     *       |> secondFunction { arg = @ }
     */
    private fun printPipeExpression(expr: PipeExpression) {
        // Collect all pipe segments (flatten left-associative chain)
        val segments = mutableListOf<Expression>()
        var current: Expression = expr
        while (current is PipeExpression) {
            segments.add(0, current.right)  // Add to front
            current = current.left
        }
        segments.add(0, current)  // Add the leftmost (non-pipe) expression

        // Estimate total length to decide single-line vs multi-line
        val estimatedLength = estimatePipeChainLength(segments)
        val useMultiLine = estimatedLength > config.MAX_LINE_LENGTH ||
                           segments.any { isComplexExpression(it) } ||
                           segments.size > 3

        if (useMultiLine) {
            // Multi-line: first segment on its own, then each pipe on new line indented
            printExpression(segments[0])
            newLine()
            indent {
                for (i in 1 until segments.size) {
                    printIndent()
                    append("|> ")
                    printExpression(segments[i])
                    if (i < segments.size - 1) {
                        newLine()
                    }
                }
            }
        } else {
            // Single line: x |> f { a = @ } |> g { b = @ }
            printExpression(segments[0])
            for (i in 1 until segments.size) {
                append(" |> ")
                printExpression(segments[i])
            }
        }
    }

    /**
     * Estimate the length of a pipe chain if printed on a single line.
     */
    private fun estimatePipeChainLength(segments: List<Expression>): Int {
        var length = 0
        for ((index, segment) in segments.withIndex()) {
            length += estimateExpressionLength(segment)
            if (index > 0) {
                length += 4  // " |> "
            }
        }
        return length + currentColumn  // Add current indent
    }

    /**
     * Rough estimate of how many characters an expression will take.
     */
    private fun estimateExpressionLength(expr: Expression): Int {
        return when (expr) {
            is IntLiteral -> expr.value.toString().length
            is NatLiteral -> expr.value.toString().length + 1
            is FloatLiteral -> expr.value.toString().length
            is FractionLiteral -> expr.numerator.toString().length + expr.denominator.toString().length + 1
            is StringLiteral -> expr.value.length + 2
            is Identifier -> expr.localName.length + (expr.qualifier?.segments?.sumOf { it.length + 2 } ?: 0)
            is PipePlaceholder -> 1
            is VariantConstruction -> expr.constructor.length + (expr.value?.let { estimateExpressionLength(it) + 1 } ?: 0)
            is MemberAccess -> estimateExpressionLength(expr.receiver) + expr.member.length + 1
            is FunctionCall -> estimateExpressionLength(expr.function) + estimateExpressionLength(expr.arguments) + 1
            is RecordLiteral -> {
                if (expr.entries.isEmpty()) 2
                else expr.entries.sumOf { entry ->
                    when (entry) {
                        is RecordFieldEntry -> entry.name.length + (entry.value?.let { estimateExpressionLength(it) + 3 } ?: 0)
                        is RecordSpreadEntry -> estimateExpressionLength(entry.expression) + 4
                    }
                } + 4
            }
            is ParenthesizedExpression -> estimateExpressionLength(expr.expression) + 2
            is PipeExpression -> estimateExpressionLength(expr.left) + estimateExpressionLength(expr.right) + 4
            else -> 20  // Conservative estimate for complex expressions
        }
    }

    /**
     * Check if an expression is "complex" and should trigger multi-line formatting.
     */
    private fun isComplexExpression(expr: Expression): Boolean {
        return when (expr) {
            is DoExpression, is MatchExpression, is PerformExpression,
            is LambdaExpression -> true
            is RecordLiteral -> expr.entries.size > 3 || expr.entries.any { entry ->
                when (entry) {
                    is RecordFieldEntry -> entry.value?.let { isComplexExpression(it) } ?: false
                    is RecordSpreadEntry -> isComplexExpression(entry.expression)
                }
            }
            is FunctionCall -> isComplexExpression(expr.arguments)
            is IfExpression -> true  // if-then-else is usually clearer on multiple lines in pipes
            else -> false
        }
    }

    private fun isSimpleExpression(expr: Expression): Boolean {
        return when (expr) {
            is IntLiteral, is FloatLiteral, is StringLiteral,
            is NatLiteral, is FractionLiteral, is Identifier, is PipePlaceholder -> true
            is VariantConstruction -> {
                val value = expr.value
                value == null || isSimpleExpression(value)
            }
            is MemberAccess -> isSimpleExpression(expr.receiver)
            else -> false
        }
    }

    private fun printLambdaParameters(params: LambdaParameters) {
        when (params) {
            is UntypedParameters -> {
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
            is TypedParameters -> {
                printRecordTypeAnnotation(params.recordType)
            }
        }
    }

    private fun printFunctionCall(expr: FunctionCall) {
        // Check if this is a desugared binary operator (e.g., NumAdd.add {l = x, r = y} -> x + y)
        val binaryOp = tryExtractBinaryOperator(expr)
        if (binaryOp != null) {
            val (op, left, right) = binaryOp
            val needsParensA = needsParens(left, op, true)
            val needsParensB = needsParens(right, op, false)

            if (needsParensA) append("(")
            printExpression(left)
            if (needsParensA) append(")")

            append(" $op ")

            if (needsParensB) append("(")
            printExpression(right)
            if (needsParensB) append(")")
            return
        }

        // Check if this is a desugared unary operator (e.g., NumSub.neg {n = x} -> -x)
        val unaryOp = tryExtractUnaryOperator(expr)
        if (unaryOp != null) {
            val (op, operand) = unaryOp
            append(op)
            val needsParens = operand is FunctionCall || operand is IfExpression
            if (needsParens) append("(")
            printExpression(operand)
            if (needsParens) append(")")
            return
        }

        // Check for chained method calls: x.foo {..}.bar {..}.baz {..}
        // These are nested FunctionCall(MemberAccess(FunctionCall(MemberAccess(...), ..), ..), ..)
        val chainSegments = tryExtractMethodChain(expr)
        if (chainSegments != null && chainSegments.second.size >= 2) {
            printMethodChain(chainSegments)
            return
        }

        // Regular function call
        val needsParens = expr.function is LambdaExpression || expr.function is IfExpression
        if (needsParens) append("(")
        printExpression(expr.function)
        if (needsParens) append(")")
        append(" ")
        printExpression(expr.arguments)
    }

    /**
     * A segment in a method chain: .method { args }
     */
    private data class MethodChainSegment(
        val methodName: String,
        val arguments: Expression
    )

    /**
     * Try to extract a method chain from a function call.
     * Returns the root expression + list of (method, args) segments, or null if not a chain.
     *
     * Example: [1,2,3].filter {p = ...}.map {f = ...}
     * Returns: Pair([1,2,3], [("filter", {p=...}), ("map", {f=...})])
     */
    private fun tryExtractMethodChain(expr: FunctionCall): Pair<Expression, List<MethodChainSegment>>? {
        // Must be receiver.method { args } pattern
        val func = expr.function
        if (func !is MemberAccess) return null

        // Don't extract binary/unary operators as chains
        if (func.member in methodToOperator || func.member in methodToUnaryOperator) return null

        val segments = mutableListOf<MethodChainSegment>()
        segments.add(MethodChainSegment(func.member, expr.arguments))

        var current = func.receiver
        while (current is FunctionCall && current.function is MemberAccess) {
            val memberAccess = current.function as MemberAccess
            // Stop if we hit an operator
            if (memberAccess.member in methodToOperator || memberAccess.member in methodToUnaryOperator) break
            segments.add(0, MethodChainSegment(memberAccess.member, current.arguments))
            current = memberAccess.receiver
        }

        // Need at least 2 chained calls to format as a chain
        if (segments.size < 2) return null

        return Pair(current, segments)
    }

    /**
     * Print a method chain with one method call per line when it exceeds line length.
     *
     *     [1, 2, 3, 4, 5]
     *       .filter { p = { x } -> x > 2 }
     *       .map { f = { x } -> x * 10 }
     *       .fold { init = 0, f = { acc, x } -> acc + x }
     */
    private fun printMethodChain(chain: Pair<Expression, List<MethodChainSegment>>) {
        val (root, segments) = chain

        // Estimate total length on one line
        var estimatedLength = estimateExpressionLength(root)
        for (seg in segments) {
            estimatedLength += 1 + seg.methodName.length + 1 + estimateExpressionLength(seg.arguments) // .method args
        }

        val useMultiLine = estimatedLength > config.MAX_LINE_LENGTH ||
                           segments.any { isComplexExpression(it.arguments) } ||
                           segments.size > 3

        if (useMultiLine) {
            printExpression(root)
            newLine()
            indent {
                for ((i, seg) in segments.withIndex()) {
                    printIndent()
                    append(".${seg.methodName} ")
                    printExpression(seg.arguments)
                    if (i < segments.size - 1) {
                        newLine()
                    }
                }
            }
        } else {
            // Single line: x.foo { a }.bar { b }
            printExpression(root)
            for (seg in segments) {
                append(".${seg.methodName} ")
                printExpression(seg.arguments)
            }
        }
    }

    /**
     * Try to extract a binary operator from a desugared function call.
     * Returns (operator, left, right) if this is a desugared binary op, null otherwise.
     *
     * New pattern: x.add { r = y } — receiver is left operand, args have only {r}
     */
    private fun tryExtractBinaryOperator(expr: FunctionCall): Triple<String, Expression, Expression>? {
        val func = expr.function
        val args = expr.arguments

        // Check for MemberAccess pattern: receiver.method
        if (func !is MemberAccess) return null

        val op = methodToOperator[func.member] ?: return null

        // Check for record args with r field (new pattern: {r = y})
        if (args !is RecordLiteral) return null
        val fieldEntries = args.getFieldEntries()
        if (fieldEntries.size != 1) return null

        val right = fieldEntries.find { it.name == "r" }?.value ?: return null
        val left = func.receiver

        return Triple(op, left, right)
    }

    /**
     * Try to extract a unary operator from a desugared function call.
     * Returns (operator, operand) if this is a desugared unary op, null otherwise.
     *
     * New pattern: x.neg {} — receiver is operand, args is empty record
     */
    private fun tryExtractUnaryOperator(expr: FunctionCall): Pair<String, Expression>? {
        val func = expr.function
        val args = expr.arguments

        // Check for MemberAccess pattern: receiver.method
        if (func !is MemberAccess) return null

        val op = methodToUnaryOperator[func.member] ?: return null

        // Check for empty record args (new pattern: {})
        if (args !is RecordLiteral) return null
        val fieldEntries = args.getFieldEntries()
        if (fieldEntries.isNotEmpty()) return null

        return Pair(op, func.receiver)
    }

    private fun printIfExpression(expr: IfExpression) {
        append("if ")
        printExpression(expr.condition)
        append(" then ")
        printExpression(expr.thenBranch)
        append(" else ")
        printExpression(expr.elseBranch)
    }

    private fun printMatchExpression(expr: MatchExpression) {
        append("match ")
        printExpression(expr.expression)
        append(" is")
        newLine()
        indent {
            for (case in expr.cases) {
                printIndent()
                case.patterns.forEachIndexed { index, pattern ->
                    if (index > 0) append(" | ")
                    printPattern(pattern)
                }
                case.guard?.let {
                    append(" if ")
                    printExpression(it)
                }
                for (ifLet in case.ifLetBindings) {
                    append(" if let ")
                    printPattern(ifLet.pattern)
                    append(" = ")
                    printExpression(ifLet.expression)
                }
                append(" -> ")
                printExpression(case.expression)
                newLine()
            }
        }
    }

    private fun printDoExpression(expr: DoExpression) {
        append("do")
        newLine()
        // Indent body by one level from current indent (not from where 'do' appears on the line)
        indent {
            for (stmt in expr.statements) {
                printIndent()
                printStatement(stmt)
                newLine()
            }
            printIndent()
            printExpression(expr.result)
        }
    }

    private fun printStatement(stmt: Statement) {
        when (stmt) {
            is LetStatement -> {
                append("let ")
                printPattern(stmt.pattern)
                stmt.typeAnnotation?.let {
                    append(": ")
                    printTypeAnnotation(it)
                }
                append(" = ")
                printExpression(stmt.expression)
            }
            is ExpressionStatement -> {
                printExpression(stmt.expression)
            }
        }
    }

    private fun printPerformExpression(expr: PerformExpression) {
        append("perform ")
        // Syntax: perform Handler in expression
        if (expr.handlers.isNotEmpty()) {
            expr.handlers.forEachIndexed { index, handler ->
                if (index > 0) append(", ")
                if (handler.performer.qualifier != null) {
                    append(handler.performer.qualifier!!.fullPath)
                    append("::")
                }
                append(handler.performer.localName)
                handler.parameters?.let {
                    append(" ")
                    printRecordLiteral(it)
                }
            }
            append(" in ")
        }
        printExpression(expr.expression)
    }

    private fun printEffectCallExpression(expr: EffectCallExpression) {
        if (expr.operation.qualifier != null) {
            append(expr.operation.qualifier!!.fullPath)
            append("::")
        }
        append(expr.operation.localName)
        append(" ")
        printExpression(expr.arguments)
    }

    // =========================================================================
    // PATTERNS
    // =========================================================================

    private fun printPattern(pattern: Pattern) {
        when (pattern) {
            is WildcardPattern -> append("_")
            is VariablePattern -> append(pattern.name)
            is ConstructorPattern -> {
                append(pattern.constructor)
                pattern.recordPattern?.let {
                    append(" ")
                    printRecordPattern(it)
                }
            }
            is RecordPattern -> printRecordPattern(pattern)
            is LiteralPattern -> printLiteralValue(pattern.value)
            is SeqPattern -> printSeqPattern(pattern)
            is StringInterpolationPattern -> printStringInterpolationPattern(pattern)
        }
    }

    private fun printRecordPattern(pattern: RecordPattern) {
        if (pattern.entries.isEmpty() && !pattern.isOpen) {
            append("{}")
            return
        }
        append("{ ")
        pattern.entries.forEachIndexed { index, entry ->
            if (index > 0) append(", ")
            append(entry.name)
            entry.pattern?.let {
                append(" = ")
                printPattern(it)
            }
        }
        if (pattern.isOpen) {
            if (pattern.entries.isNotEmpty()) append(", ")
            append("...")
        }
        append(" }")
    }

    private fun printSeqPattern(pattern: SeqPattern) {
        append("[")
        pattern.elements.forEachIndexed { index, element ->
            if (index > 0) append(", ")
            printPattern(element)
        }
        pattern.restPattern?.let {
            if (pattern.elements.isNotEmpty()) append(", ")
            append("...")
            append(it)
        }
        append("]")
    }

    private fun printStringInterpolationPattern(pattern: StringInterpolationPattern) {
        append("\"")
        for (part in pattern.parts) {
            when (part) {
                is StringPatternLiteral -> append(escapeString(part.value))
                is StringPatternCapture -> {
                    append("\$(")
                    append(part.variableName)
                    append(")")
                }
            }
        }
        append("\"")
    }

    private fun printLiteralValue(value: LiteralValue) {
        when (value) {
            is IntLiteralValue -> append(value.value.toString())
            is NatLiteralValue -> append("${value.value}n")
            is FloatLiteralValue -> append(formatFloat(value.value))
            is StringLiteralValue -> {
                append("\"")
                append(escapeString(value.value))
                append("\"")
            }
            is BooleanLiteralValue -> append(if (value.value) "True" else "False")
        }
    }

    // =========================================================================
    // TYPE ANNOTATIONS
    // =========================================================================

    private fun printTypeAnnotation(type: TypeAnnotation) {
        when (type) {
            is IdentifierTypeAnnotation -> append(type.name)
            is QualifiedTypeAnnotation -> {
                append(type.modulePath.joinToString("::"))
                append("::")
                append(type.typeName)
                if (type.args.isNotEmpty()) {
                    append(" ")
                    type.args.forEachIndexed { index, arg ->
                        if (index > 0) append(" ")
                        val needsParens = arg is FunctionTypeAnnotation ||
                            (arg is TypeApplicationAnnotation && arg.args.isNotEmpty())
                        if (needsParens) append("(")
                        printTypeAnnotation(arg)
                        if (needsParens) append(")")
                    }
                }
            }
            is TypeApplicationAnnotation -> {
                append(type.constructor)
                if (type.args.isNotEmpty()) {
                    append(" ")
                    type.args.forEachIndexed { index, arg ->
                        if (index > 0) append(" ")
                        val needsParens = arg is FunctionTypeAnnotation ||
                            (arg is TypeApplicationAnnotation && arg.args.isNotEmpty())
                        if (needsParens) append("(")
                        printTypeAnnotation(arg)
                        if (needsParens) append(")")
                    }
                }
            }
            is RecordTypeAnnotation -> printRecordTypeAnnotation(type)
            is VariantTypeAnnotation -> printVariantTypeAnnotation(type)
            is FunctionTypeAnnotation -> printFunctionTypeAnnotation(type)
            is SeqTypeAnnotation -> {
                append("[")
                printTypeAnnotation(type.elementType)
                append("]")
            }
            is EffectRowAnnotation -> printEffectRow(type)
        }
    }

    private fun printRecordTypeAnnotation(type: RecordTypeAnnotation) {
        if (type.entries.isEmpty() && !type.isOpen) {
            append("{}")
            return
        }

        val estimatedWidth = calculateRecordTypeWidth(type)
        val singleLine = estimatedWidth <= config.RECORD_SINGLE_LINE_THRESHOLD

        if (singleLine) {
            append("{ ")
            type.entries.forEachIndexed { index, entry ->
                if (index > 0) append(", ")
                append(entry.name)
                append(": ")
                printTypeAnnotation(entry.type)
            }
            if (type.isOpen) {
                if (type.entries.isNotEmpty()) append(", ")
                append("...")
            }
            append(" }")
        } else {
            append("{")
            newLine()
            indent {
                type.entries.forEachIndexed { index, entry ->
                    printIndent()
                    append(entry.name)
                    append(": ")
                    printTypeAnnotation(entry.type)
                    if (config.TRAILING_COMMA_IN_MULTILINE || index < type.entries.size - 1 || type.isOpen) {
                        append(",")
                    }
                    newLine()
                }
                if (type.isOpen) {
                    printIndent()
                    append("...")
                    newLine()
                }
            }
            printIndent()
            append("}")
        }
    }

    private fun calculateRecordTypeWidth(type: RecordTypeAnnotation): Int {
        return 4 + type.entries.sumOf { it.name.length + 2 + estimateTypeWidth(it.type) + 2 }
    }

    private fun printVariantTypeAnnotation(type: VariantTypeAnnotation) {
        // Mezze variant syntax: | Constructor1, Constructor2 { data: Type } |
        append("| ")
        type.constructors.forEachIndexed { index, constructor ->
            if (index > 0) append(", ")
            append(constructor.name)
            constructor.dataType?.let {
                append(" ")
                printTypeAnnotation(it)
            }
        }
        if (type.isOpen) {
            append(", ...")
        }
        append(" |")
    }

    private fun printFunctionTypeAnnotation(type: FunctionTypeAnnotation) {
        printTypeAnnotation(type.paramType)
        append(" -> ")
        printTypeAnnotation(type.returnType)
        type.effects?.let {
            append(" ")
            printEffectRow(it)
        }
    }

    private fun printEffectRow(row: EffectRowAnnotation) {
        append("<")
        val allEffects = row.effects + row.effectAnnotations.map { effectAnnotation ->
            buildString {
                append(effectAnnotation.name)
                if (effectAnnotation.associatedTypes.isNotEmpty()) {
                    append(" having ")
                    effectAnnotation.associatedTypes.entries.forEachIndexed { index, (name, typeAnn) ->
                        if (index > 0) append(", ")
                        // name already includes the ' prefix from the parser
                append("$name = ")
                        // Can't recursively call printTypeAnnotation here, so format inline
                        append(formatTypeAnnotationSimple(typeAnn))
                    }
                }
            }
        }
        append(allEffects.joinToString(", "))
        if (row.isPolymorphic) {
            if (allEffects.isNotEmpty()) append(", ")
            append("...")
        }
        if (row.isPerformed) {
            if (allEffects.isNotEmpty() || row.isPolymorphic) append(", ")
            append("*")
        }
        append(">")
    }

    // =========================================================================
    // COMMENTS
    // =========================================================================

    private fun printLeadingComments(untilLine: Int) {
        while (nextCommentIndex < comments.size) {
            val comment = comments[nextCommentIndex]
            if (comment.line < untilLine && !comment.isTrailing) {
                printIndent()
                append(comment.text)
                newLine()
                nextCommentIndex++
            } else {
                break
            }
        }
    }

    private fun printTrailingComment() {
        if (nextCommentIndex < comments.size) {
            val comment = comments[nextCommentIndex]
            if (comment.isTrailing && comment.line == currentLine) {
                append("  ")
                append(comment.text)
                nextCommentIndex++
            }
        }
    }

    private fun printRemainingComments() {
        while (nextCommentIndex < comments.size) {
            val comment = comments[nextCommentIndex]
            newLine()
            append(comment.text)
            nextCommentIndex++
        }
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private fun append(s: String) {
        output.append(s)
        // Track column - count from last newline or add to current
        val lastNewline = s.lastIndexOf('\n')
        if (lastNewline >= 0) {
            currentColumn = s.length - lastNewline - 1
        } else {
            currentColumn += s.length
        }
    }

    private fun newLine() {
        printTrailingComment()
        output.append("\n")
        currentLine++
        currentColumn = 0
    }

    private fun printIndent() {
        repeat(indentLevel) {
            output.append(config.INDENT_STRING)
        }
        currentColumn = indentLevel * config.INDENT_WIDTH
    }

    private inline fun indent(block: () -> Unit) {
        indentLevel++
        block()
        indentLevel--
    }

    private fun escapeString(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
            .replace("\r", "\\r")
            .replace("\$", "\\\$")
    }

    private fun formatFloat(value: Double): String {
        val s = value.toString()
        return if (!s.contains('.') && !s.contains('e') && !s.contains('E')) {
            "$s.0"
        } else {
            s
        }
    }

    private fun formatTypeAnnotationSimple(type: TypeAnnotation): String {
        return when (type) {
            is IdentifierTypeAnnotation -> type.name
            is TypeApplicationAnnotation -> {
                if (type.args.isEmpty()) type.constructor
                else "${type.constructor} ${type.args.joinToString(" ") { formatTypeAnnotationSimple(it) }}"
            }
            else -> type.toString()
        }
    }

    private fun needsParens(expr: Expression, op: String, isLeft: Boolean): Boolean {
        if (expr !is FunctionCall) return false

        // Check if inner expression is also a binary operator
        val innerOp = tryExtractBinaryOperator(expr)?.first ?: return false

        val outerPrec = operatorPrecedence(op)
        val innerPrec = operatorPrecedence(innerOp)

        return if (isLeft) {
            innerPrec < outerPrec
        } else {
            innerPrec <= outerPrec
        }
    }

    private fun operatorPrecedence(op: String): Int {
        return when (op) {
            "||" -> 1
            "&&" -> 2
            "==", "!=" -> 3
            "<", ">", "<=", ">=" -> 4
            "+", "-" -> 5
            "*", "/", "%" -> 6
            else -> 0
        }
    }

    private fun isSimpleType(type: TypeAnnotation?): Boolean {
        return when (type) {
            null -> true
            is IdentifierTypeAnnotation -> true
            is RecordTypeAnnotation -> type.entries.size <= 2 && type.entries.all { isSimpleType(it.type) }
            is TypeApplicationAnnotation -> type.args.size <= 2 && type.args.all { isSimpleType(it) }
            else -> false
        }
    }

    private fun calculateRecordDefWidth(def: RecordDefinition): Int {
        return 4 + def.fields.sumOf { it.name.length + 2 + estimateTypeWidth(it.type) + 2 }
    }

    private fun calculateRecordLiteralWidth(expr: RecordLiteral): Int {
        return 2 + expr.entries.sumOf { entry ->
            when (entry) {
                is RecordFieldEntry -> {
                    val valueExpr = entry.value
                    entry.name.length + (if (valueExpr != null) 3 + estimateExprWidth(valueExpr) else 0) + 2
                }
                is RecordSpreadEntry -> 3 + estimateExprWidth(entry.expression) + 2
            }
        }
    }

    private fun calculateSeqLiteralWidth(expr: SeqLiteral): Int {
        return 2 + expr.elements.sumOf { element ->
            when (element) {
                is SeqRegularElement -> estimateExprWidth(element.expression) + 2
                is SeqSpreadElement -> 3 + estimateExprWidth(element.expression) + 2
            }
        }
    }

    private fun estimateTypeWidth(type: TypeAnnotation): Int {
        return when (type) {
            is IdentifierTypeAnnotation -> type.name.length
            is TypeApplicationAnnotation -> type.constructor.length + 1 + type.args.sumOf { estimateTypeWidth(it) + 1 }
            is RecordTypeAnnotation -> 4 + type.entries.sumOf { it.name.length + 2 + estimateTypeWidth(it.type) + 2 }
            is FunctionTypeAnnotation -> estimateTypeWidth(type.paramType) + 4 + estimateTypeWidth(type.returnType)
            else -> 10
        }
    }

    private fun estimateExprWidth(expr: Expression): Int {
        return when (expr) {
            is IntLiteral -> expr.value.toString().length
            is FloatLiteral -> formatFloat(expr.value).length
            is StringLiteral -> expr.value.length + 2
            is Identifier -> expr.toBindingKey().toKeyString().length
            is RecordLiteral -> calculateRecordLiteralWidth(expr)
            is SeqLiteral -> calculateSeqLiteralWidth(expr)
            else -> 20
        }
    }
}
