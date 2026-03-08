package com.mezze.language.formatter

import com.mezze.language.ast.*

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
    private var comments: List<Comment> = emptyList()
    private var nextCommentIndex = 0

    /**
     * Print a complete program to formatted source code.
     */
    fun print(program: Program): String {
        output.clear()
        indentLevel = 0
        currentLine = 1
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
        var lastItemLine = 0
        var isFirst = true

        for (item in program.items) {
            // Print leading comments for this item
            printLeadingComments(getItemLine(item))

            // Add blank line between top-level items (but not before first)
            if (!isFirst) {
                // Preserve single blank lines between items
                newLine()
            }
            isFirst = false

            printProgramItem(item)
            lastItemLine = currentLine
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
            append(typeParams.joinToString(" ") { "'$it" })
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
        // For variant definitions, we print each constructor on its own line
        // unless it's very simple
        val simple = def.constructors.size <= config.VARIANT_SINGLE_LINE_MAX_CONSTRUCTORS &&
                def.constructors.all { it.dataType == null || isSimpleType(it.dataType) }

        if (simple && def.constructors.isNotEmpty()) {
            def.constructors.forEachIndexed { index, constructor ->
                if (index > 0) append(" | ")
                append(constructor.name)
                constructor.dataType?.let {
                    append(" ")
                    printTypeAnnotation(it)
                }
            }
        } else {
            newLine()
            indent {
                def.constructors.forEachIndexed { index, constructor ->
                    printIndent()
                    append("| ")
                    append(constructor.name)
                    constructor.dataType?.let {
                        append(" ")
                        printTypeAnnotation(it)
                    }
                    newLine()
                }
            }
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
            append(decl.associatedTypes.joinToString(", ") { "'$it" })
        }

        append(" where {")
        newLine()
        indent {
            for (op in decl.operations) {
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
        append(" ")
        append(decl.typeParams.joinToString(" ") { "'$it" })

        if (decl.associatedTypes.isNotEmpty()) {
            append(" having ")
            append(decl.associatedTypes.joinToString(", ") { "'$it" })
        }

        if (decl.dependencies.isNotEmpty()) {
            append(" for ")
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
            for (op in decl.operations) {
                printIndent()
                append(op.name)
                append(": ")
                printTypeAnnotation(op.paramType)
                append(" -> ")
                printTypeAnnotation(op.returnType)
                newLine()
            }
        }
        printIndent()
        append("}")
        newLine()
    }

    private fun printImplDeclaration(decl: ImplDeclaration) {
        append("impl ")
        append(decl.effectName)
        append(" ")
        append(decl.performerName)

        if (decl.performerParams.isNotEmpty()) {
            append(" {")
            decl.performerParams.forEachIndexed { index, param ->
                if (index > 0) append(", ")
                append(param.name)
                append(": ")
                printTypeAnnotation(param.type)
            }
            append("}")
        }

        if (decl.associatedTypeBindings.isNotEmpty()) {
            append(" having ")
            decl.associatedTypeBindings.entries.forEachIndexed { index, (name, type) ->
                if (index > 0) append(", ")
                append("'$name = ")
                printTypeAnnotation(type)
            }
        }

        append(" where {")
        newLine()
        indent {
            for (op in decl.operations) {
                printIndent()
                append(op.name)
                op.paramType?.let {
                    append(": ")
                    printTypeAnnotation(it)
                }
                op.returnType?.let {
                    append(" -> ")
                    printTypeAnnotation(it)
                }
                op.effects?.let {
                    append(" ")
                    printEffectRow(it)
                }
                append(" = ")
                printExpression(op.implementation)
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

        if (decl.abilityModulePath.isNotEmpty()) {
            append(decl.abilityModulePath.joinToString("::"))
            append("::")
        }
        append(decl.abilityName)
        append(" ")
        decl.targetTypes.forEachIndexed { index, type ->
            if (index > 0) append(" ")
            printTypeAnnotation(type)
        }

        if (decl.associatedTypeBindings.isNotEmpty()) {
            append(" having ")
            decl.associatedTypeBindings.entries.forEachIndexed { index, (name, type) ->
                if (index > 0) append(", ")
                append("'$name = ")
                printTypeAnnotation(type)
            }
        }

        append(" where {")
        newLine()
        indent {
            for (op in decl.operations) {
                printIndent()
                append(op.name)
                op.paramType?.let {
                    append(": ")
                    printTypeAnnotation(it)
                }
                op.returnType?.let {
                    append(" -> ")
                    printTypeAnnotation(it)
                }
                append(" = ")
                printExpression(op.implementation)
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
            is UseModule -> append(path.path.joinToString("::"))
            is UseSelective -> {
                append(path.modulePath.joinToString("::"))
                append("::{")
                path.items.forEachIndexed { index, item ->
                    if (index > 0) append(", ")
                    when (item) {
                        is UseItem.Named -> {
                            append(item.name)
                            item.alias?.let {
                                append(" as ")
                                append(it)
                            }
                        }
                        is UseItem.Wildcard -> append("*")
                    }
                }
                append("}")
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
            is BooleanLiteral -> append(if (expr.value) "True" else "False")
            is Identifier -> printIdentifier(expr)
            is RecordLiteral -> printRecordLiteral(expr)
            is ArrayLiteral -> printArrayLiteral(expr)
            is ArrayAccess -> printArrayAccess(expr)
            is MemberAccess -> printMemberAccess(expr)
            is RecordUpdate -> printRecordUpdate(expr)
            is RecordUpdateExpression -> printRecordUpdateExpression(expr)
            is VariantConstruction -> printVariantConstruction(expr)
            is LambdaExpression -> printLambdaExpression(expr)
            is FunctionCall -> printFunctionCall(expr)
            is IfExpression -> printIfExpression(expr)
            is MatchExpression -> printMatchExpression(expr)
            is DoExpression -> printDoExpression(expr)
            is PerformExpression -> printPerformExpression(expr)
            is EffectCallExpression -> printEffectCallExpression(expr)
            is PlaceholderExpression -> append("_")
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
            append(expr.name)
        } else {
            append(expr.localName)
        }
    }

    private fun printRecordLiteral(expr: RecordLiteral) {
        if (expr.entries.isEmpty() && expr.restField == null) {
            append("{}")
            return
        }

        val singleLine = calculateRecordLiteralWidth(expr) <= config.RECORD_SINGLE_LINE_THRESHOLD
        if (singleLine) {
            append("{")
            var first = true
            for (entry in expr.entries) {
                if (!first) append(", ")
                first = false
                printRecordLiteralEntry(entry)
            }
            expr.restField?.let {
                if (!first) append(", ")
                append("...")
                append(it)
            }
            append("}")
        } else {
            append("{")
            newLine()
            indent {
                for ((index, entry) in expr.entries.withIndex()) {
                    printIndent()
                    printRecordLiteralEntry(entry)
                    if (config.TRAILING_COMMA_IN_MULTILINE || index < expr.entries.size - 1 || expr.restField != null) {
                        append(",")
                    }
                    newLine()
                }
                expr.restField?.let {
                    printIndent()
                    append("...")
                    append(it)
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

    private fun printArrayLiteral(expr: ArrayLiteral) {
        if (expr.elements.isEmpty()) {
            append("[]")
            return
        }

        val singleLine = calculateArrayLiteralWidth(expr) <= config.ARRAY_SINGLE_LINE_THRESHOLD
        if (singleLine) {
            append("[")
            expr.elements.forEachIndexed { index, element ->
                if (index > 0) append(", ")
                printArrayElement(element)
            }
            append("]")
        } else {
            append("[")
            newLine()
            indent {
                expr.elements.forEachIndexed { index, element ->
                    printIndent()
                    printArrayElement(element)
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

    private fun printArrayElement(element: ArrayElement) {
        when (element) {
            is ArrayRegularElement -> printExpression(element.expression)
            is ArraySpreadElement -> {
                append("...")
                printExpression(element.expression)
            }
        }
    }

    private fun printArrayAccess(expr: ArrayAccess) {
        printExpression(expr.array)
        append("[")
        printExpression(expr.index)
        append("]")
    }

    private fun printMemberAccess(expr: MemberAccess) {
        printExpression(expr.receiver)
        append(".")
        append(expr.member)
    }

    private fun printRecordUpdate(expr: RecordUpdate) {
        append("{")
        printExpression(expr.record)
        append(" | ")
        expr.updates.forEachIndexed { index, update ->
            if (index > 0) append(", ")
            append(update.name)
            update.value?.let {
                append(" = ")
                printExpression(it)
            }
        }
        append("}")
    }

    private fun printRecordUpdateExpression(expr: RecordUpdateExpression) {
        append("{")
        printExpression(expr.baseRecord)
        append(" | ")
        expr.updates.forEachIndexed { index, update ->
            if (index > 0) append(", ")
            append(update.fieldPath.joinToString("."))
            append(" = ")
            printExpression(update.value)
        }
        append("}")
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

        append(" -> ")
        printExpression(expr.body)
    }

    private fun printLambdaParameters(params: LambdaParameters) {
        when (params) {
            is UntypedParameters -> {
                append("{")
                append(params.names.joinToString(", "))
                if (params.isOpen) {
                    if (params.names.isNotEmpty()) append(", ")
                    append("...")
                }
                append("}")
            }
            is TypedParameters -> {
                printRecordTypeAnnotation(params.recordType)
            }
        }
    }

    private fun printFunctionCall(expr: FunctionCall) {
        // Handle operator calls specially
        if (expr.function is Identifier && isOperator((expr.function as Identifier).localName)) {
            val op = (expr.function as Identifier).localName
            val args = expr.arguments
            if (args is RecordLiteral && args.entries.size == 2) {
                val fieldEntries = args.getFieldEntries()
                if (fieldEntries.size == 2) {
                    val a = fieldEntries.find { it.name == "a" }?.value
                    val b = fieldEntries.find { it.name == "b" }?.value
                    if (a != null && b != null) {
                        val needsParensA = needsParens(a, op, true)
                        val needsParensB = needsParens(b, op, false)

                        if (needsParensA) append("(")
                        printExpression(a)
                        if (needsParensA) append(")")

                        append(" $op ")

                        if (needsParensB) append("(")
                        printExpression(b)
                        if (needsParensB) append(")")
                        return
                    }
                }
            }
        }

        // Regular function call
        val needsParens = expr.function is LambdaExpression || expr.function is IfExpression
        if (needsParens) append("(")
        printExpression(expr.function)
        if (needsParens) append(")")
        append(" ")
        printExpression(expr.arguments)
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
        newLine()
        indent {
            for (case in expr.cases) {
                printIndent()
                append("is ")
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
        printExpression(expr.expression)
        if (expr.handlers.isNotEmpty()) {
            append(" with ")
            expr.handlers.forEachIndexed { index, handler ->
                if (index > 0) append(", ")
                append(handler.name)
                handler.parameters?.let {
                    append(" ")
                    printRecordLiteral(it)
                }
            }
        }
    }

    private fun printEffectCallExpression(expr: EffectCallExpression) {
        append(expr.effectName)
        append("::")
        append(expr.operationName)
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
            is ArrayPattern -> printArrayPattern(pattern)
            is StringInterpolationPattern -> printStringInterpolationPattern(pattern)
        }
    }

    private fun printRecordPattern(pattern: RecordPattern) {
        append("{")
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
        append("}")
    }

    private fun printArrayPattern(pattern: ArrayPattern) {
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
            is ArrayTypeAnnotation -> {
                append("[")
                printTypeAnnotation(type.elementType)
                append("]")
            }
            is EffectRowAnnotation -> printEffectRow(type)
        }
    }

    private fun printRecordTypeAnnotation(type: RecordTypeAnnotation) {
        if (type.entries.isEmpty() && type.restField == null) {
            append("{}")
            return
        }

        append("{")
        type.entries.forEachIndexed { index, entry ->
            if (index > 0) append(", ")
            append(entry.name)
            append(": ")
            printTypeAnnotation(entry.type)
        }
        type.restField?.let {
            if (type.entries.isNotEmpty()) append(", ")
            append("...")
            append(it)
        }
        append("}")
    }

    private fun printVariantTypeAnnotation(type: VariantTypeAnnotation) {
        type.constructors.forEachIndexed { index, constructor ->
            if (index > 0) append(" | ")
            append(constructor.name)
            constructor.dataType?.let {
                append(" ")
                printTypeAnnotation(it)
            }
        }
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
                        append("'$name = ")
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
    }

    private fun newLine() {
        printTrailingComment()
        output.append("\n")
        currentLine++
    }

    private fun printIndent() {
        repeat(indentLevel) {
            output.append(config.INDENT_CHAR)
        }
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

    private fun isOperator(name: String): Boolean {
        return name in setOf("+", "-", "*", "/", "%", "==", "!=", "<", ">", "<=", ">=", "&&", "||")
    }

    private fun needsParens(expr: Expression, op: String, isLeft: Boolean): Boolean {
        if (expr !is FunctionCall) return false
        val innerOp = (expr.function as? Identifier)?.localName ?: return false
        if (!isOperator(innerOp)) return false

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
        return type == null || type is IdentifierTypeAnnotation
    }

    private fun calculateRecordDefWidth(def: RecordDefinition): Int {
        return 4 + def.fields.sumOf { it.name.length + 2 + estimateTypeWidth(it.type) + 2 }
    }

    private fun calculateRecordLiteralWidth(expr: RecordLiteral): Int {
        return 2 + expr.entries.sumOf { entry ->
            when (entry) {
                is RecordFieldEntry -> entry.name.length + (if (entry.value != null) 3 + estimateExprWidth(entry.value) else 0) + 2
                is RecordSpreadEntry -> 3 + estimateExprWidth(entry.expression) + 2
            }
        }
    }

    private fun calculateArrayLiteralWidth(expr: ArrayLiteral): Int {
        return 2 + expr.elements.sumOf { element ->
            when (element) {
                is ArrayRegularElement -> estimateExprWidth(element.expression) + 2
                is ArraySpreadElement -> 3 + estimateExprWidth(element.expression) + 2
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
            is BooleanLiteral -> if (expr.value) 4 else 5
            is Identifier -> expr.name.length
            is RecordLiteral -> calculateRecordLiteralWidth(expr)
            is ArrayLiteral -> calculateArrayLiteralWidth(expr)
            else -> 20
        }
    }
}
