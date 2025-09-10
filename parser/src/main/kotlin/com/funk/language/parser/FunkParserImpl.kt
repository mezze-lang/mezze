package com.funk.language.parser

import com.funk.language.ast.*
import com.funk.language.parser.generated.FunkLexer
import com.funk.language.parser.generated.FunkParser
import com.funk.language.parser.generated.FunkBaseVisitor
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream

class FunkParserImpl {
    fun parse(source: String): Program {
        val lexer = FunkLexer(CharStreams.fromString(source))
        val tokens = CommonTokenStream(lexer)
        val parser = FunkParser(tokens)
        val tree = parser.program()
        return FunkASTBuilder().visit(tree) as Program
    }
}

private class FunkASTBuilder : FunkBaseVisitor<Any>() {

    /**
     * Heuristic to determine if an identifier is likely a record variable vs a function.
     * This is conservative - when in doubt, we prefer function calls.
     */
    private fun isLikelyRecordVariable(name: String): Boolean {
        // Common record variable patterns: person, user, data, config, etc.
        // Avoid common function names: add, create, get, make, etc.
        val commonRecordNames = setOf(
            "person", "user", "data", "config", "record", "item", "entity", 
            "obj", "object", "state", "model", "info", "details"
        )
        val commonFunctionNames = setOf(
            "add", "create", "make", "get", "set", "update", "delete", "remove",
            "build", "construct", "generate", "compute", "calculate", "process"
        )
        
        return when {
            name in commonRecordNames -> true
            name in commonFunctionNames -> false
            // Default: if it ends with common record suffixes, likely a record
            name.endsWith("Data") || name.endsWith("Info") || name.endsWith("Config") -> true
            // Otherwise, prefer function call (conservative)
            else -> false
        }
    }

    override fun visitProgram(ctx: FunkParser.ProgramContext): Any {
        val items = ctx.statement().mapNotNull { visit(it) as? ProgramItem }
        return Program(items)
    }

    override fun visitStatement(ctx: FunkParser.StatementContext): Any {
        return when {
            ctx.letBinding() != null -> visit(ctx.letBinding())
            ctx.typeDeclaration() != null -> visit(ctx.typeDeclaration())
            ctx.moduleDeclaration() != null -> visit(ctx.moduleDeclaration())
            ctx.modDeclaration() != null -> visit(ctx.modDeclaration())
            ctx.accessModuleDeclaration() != null -> visit(ctx.accessModuleDeclaration())
            ctx.exposeDeclaration() != null -> visit(ctx.exposeDeclaration())
            ctx.accessDeclaration() != null -> visit(ctx.accessDeclaration())
            ctx.resumeStatement() != null -> visit(ctx.resumeStatement())
            ctx.expressionStatement() != null -> visit(ctx.expressionStatement())
            else -> throw IllegalArgumentException("Unknown statement: ${ctx.text}")
        }
    }

    override fun visitExpressionStatement(ctx: FunkParser.ExpressionStatementContext): Any {
        // Expression statements are standalone expressions that should be evaluated
        val expression = visit(ctx.expression()) as Expression
        return TopLevelExpression(expression)
    }

    // Module system (simplified for now)
    override fun visitModuleDeclaration(ctx: FunkParser.ModuleDeclarationContext): Any {
        val name = ctx.Identifier().text
        return ModuleDeclaration(name, emptyList())
    }

    override fun visitExposeDeclaration(ctx: FunkParser.ExposeDeclarationContext): Any {
        val items = ctx.exposeList().exposeItem().map { visit(it) as ExposeItem }
        return ExposeDeclaration(items)
    }

    override fun visitExposeItem(ctx: FunkParser.ExposeItemContext): Any {
        val kind = if (ctx.TYPE() != null) ExposeKind.TYPE else null
        val name = ctx.Identifier().text
        return ExposeItem(kind, name)
    }

    override fun visitAccessDeclaration(ctx: FunkParser.AccessDeclarationContext): Any {
        val moduleName = ctx.Identifier().text
        val items = ctx.accessList().accessItem().map { visit(it) as AccessItem }
        return AccessDeclaration(moduleName, items)
    }

    override fun visitAccessItem(ctx: FunkParser.AccessItemContext): Any {
        val name = ctx.Identifier(0).text
        val alias = ctx.Identifier(1)?.text
        return AccessItem(name, alias)
    }

    override fun visitModDeclaration(ctx: FunkParser.ModDeclarationContext): Any {
        val moduleName = ctx.Identifier().text
        return ModDeclaration(moduleName)
    }

    override fun visitAccessModuleDeclaration(ctx: FunkParser.AccessModuleDeclarationContext): Any {
        val modulePath = ctx.modulePath().Identifier().map { it.text }
        val alias = ctx.Identifier()?.text
        val isExposed = ctx.EXPOSE() != null
        return AccessModuleDeclaration(modulePath, alias, isExposed)
    }

    // Declarations
    override fun visitLetBinding(ctx: FunkParser.LetBindingContext): Any {
        val variableName = ctx.Identifier().text
        val typeAnnotation = ctx.typeAnnotation()?.let { visit(it) as TypeAnnotation }
        val expression = visit(ctx.expression()) as Expression
        return LetBinding(variableName, typeAnnotation, null, expression)
    }

    override fun visitTypeDeclaration(ctx: FunkParser.TypeDeclarationContext): Any {
        val name = ctx.Identifier().text
        val typeParams = ctx.typeParams()?.TypeVariable()?.map { it.text } ?: emptyList()
        val definition = visit(ctx.typeDefinition()) as TypeDefinition
        return TypeDeclaration(name, typeParams, definition)
    }

    override fun visitTypeDefinition(ctx: FunkParser.TypeDefinitionContext): Any {
        return when {
            ctx.recordType() != null -> {
                val recordType = visit(ctx.recordType()) as RecordTypeAnnotation
                RecordDefinition(recordType.entries.map { 
                    RecordFieldDef(it.name, it.type)
                })
            }
            ctx.variantType() != null -> visit(ctx.variantType())
            else -> throw IllegalArgumentException("Unknown type definition: ${ctx.text}")
        }
    }

    override fun visitVariantType(ctx: FunkParser.VariantTypeContext): Any {
        val constructors = ctx.variantConstructor().map { visit(it) as VariantConstructorDef }
        return VariantDefinition(constructors)
    }

    override fun visitVariantConstructor(ctx: FunkParser.VariantConstructorContext): Any {
        val name = ctx.qualifiedIdentifier().text
        val dataType = ctx.recordType()?.let { visit(it) as TypeAnnotation }
        return VariantConstructorDef(name, dataType)
    }

    // Statements
    override fun visitResumeStatement(ctx: FunkParser.ResumeStatementContext): Any {
        val expression = visit(ctx.expression()) as Expression
        return ExpressionStatement(expression) // For now, treat as expression statement
    }


    // Expressions
    override fun visitExpression(ctx: FunkParser.ExpressionContext): Any {
        return visit(ctx.pipeOperatorExpression())
    }

    override fun visitPipeOperatorExpression(ctx: FunkParser.PipeOperatorExpressionContext): Any {
        var result = visit(ctx.variantExpression(0)) as Expression
        
        for (i in 1 until ctx.variantExpression().size) {
            val right = visit(ctx.variantExpression(i)) as Expression
            // Desugar pipe: substitute placeholders in right expression with left value
            result = substitutePlaceholdersInExpression(right, result)
        }
        
        return result
    }

    override fun visitVariantExpression(ctx: FunkParser.VariantExpressionContext): Any {
        return visit(ctx.matchExpression())
    }

    override fun visitMatchExpression(ctx: FunkParser.MatchExpressionContext): Any {
        if (ctx.MATCH() != null) {
            val expression = visit(ctx.expression()) as Expression
            val cases = ctx.matchCase().map { visit(it) as MatchCase }
            return MatchExpression(expression, cases)
        }
        return visit(ctx.handleExpression())
    }

    override fun visitMatchCase(ctx: FunkParser.MatchCaseContext): Any {
        val pattern = visit(ctx.pattern()) as Pattern
        val expression = visit(ctx.expression()) as Expression
        return MatchCase(pattern, expression)
    }

    override fun visitHandleExpression(ctx: FunkParser.HandleExpressionContext): Any {
        // For now, skip handle expressions and go to if expressions
        return visit(ctx.ifExpression())
    }

    override fun visitIfExpression(ctx: FunkParser.IfExpressionContext): Any {
        if (ctx.IF() != null) {
            val condition = visit(ctx.expression(0)) as Expression
            val thenBranch = visit(ctx.expression(1)) as Expression
            val elseBranch = visit(ctx.expression(2)) as Expression
            return IfExpression(condition, thenBranch, elseBranch)
        }
        return visit(ctx.updateExpression())
    }

    override fun visitUpdateExpression(ctx: FunkParser.UpdateExpressionContext): Any {
        val expr = visit(ctx.comparisonExpression()) as Expression
        if (ctx.recordLiteral() != null) {
            val updates = visit(ctx.recordLiteral()) as RecordLiteral
            return RecordUpdate(expr, updates.entries)
        }
        return expr
    }

    override fun visitComparisonExpression(ctx: FunkParser.ComparisonExpressionContext): Any {
        var result = visit(ctx.additiveExpression(0)) as Expression
        
        for (i in 1 until ctx.additiveExpression().size) {
            val right = visit(ctx.additiveExpression(i)) as Expression
            val operator = when {
                ctx.GT(i-1) != null -> ">"
                ctx.LT(i-1) != null -> "<"
                ctx.GTE(i-1) != null -> ">="
                ctx.LTE(i-1) != null -> "<="
                ctx.EQ(i-1) != null -> "=="
                ctx.NEQ(i-1) != null -> "!="
                else -> "=="
            }
            result = BinaryOperation(result, operator, right)
        }
        
        return result
    }

    override fun visitAdditiveExpression(ctx: FunkParser.AdditiveExpressionContext): Any {
        var result = visit(ctx.multiplicativeExpression(0)) as Expression
        
        for (i in 1 until ctx.multiplicativeExpression().size) {
            val right = visit(ctx.multiplicativeExpression(i)) as Expression
            val operator = when {
                ctx.PLUS(i-1) != null -> "+"
                ctx.MINUS(i-1) != null -> "-"
                ctx.CONCAT(i-1) != null -> "++"
                else -> "+"
            }
            result = BinaryOperation(result, operator, right)
        }
        
        return result
    }

    override fun visitMultiplicativeExpression(ctx: FunkParser.MultiplicativeExpressionContext): Any {
        var result = visit(ctx.memberAccessExpression(0)) as Expression
        
        for (i in 1 until ctx.memberAccessExpression().size) {
            val right = visit(ctx.memberAccessExpression(i)) as Expression
            val operator = when {
                ctx.MULTIPLY(i-1) != null -> "*"
                ctx.DIVIDE(i-1) != null -> "/"
                else -> "*"
            }
            result = BinaryOperation(result, operator, right)
        }
        
        return result
    }

    override fun visitMemberAccessExpression(ctx: FunkParser.MemberAccessExpressionContext): Any {
        var result = visit(ctx.primaryExpression()) as Expression
        
        ctx.Identifier().forEach { identifier ->
            result = MemberAccess(result, identifier.text)
        }
        
        return result
    }

    override fun visitPrimaryExpression(ctx: FunkParser.PrimaryExpressionContext): Any {
        return when {
            ctx.literal() != null -> visit(ctx.literal())
            ctx.Identifier() != null -> {
                val name = ctx.Identifier().text
                Identifier(name)
            }
            ctx.PLACEHOLDER() != null -> {
                // Pipe placeholder
                PlaceholderExpression()
            }
            ctx.expression() != null -> visit(ctx.expression())
            ctx.recordLiteral() != null -> {
                // Check if this is a function call or record literal
                val recordLiteral = visit(ctx.recordLiteral()) as RecordLiteral
                if (ctx.primaryExpression() != null) {
                    val primaryExpr = visit(ctx.primaryExpression()) as Expression
                    
                    // Disambiguate between function call and variant construction
                    if (primaryExpr is Identifier && primaryExpr.name.first().isUpperCase()) {
                        // Uppercase identifier + record = variant construction (e.g., Blue {val = 10})
                        VariantConstruction(primaryExpr.name, recordLiteral)
                    } else {
                        // Lowercase identifier + record = function call (e.g., func {x = 1})
                        FunctionCall(primaryExpr, recordLiteral)
                    }
                } else {
                    recordLiteral
                }
            }
            ctx.doExpression() != null -> visit(ctx.doExpression())
            ctx.lambdaExpression() != null -> visit(ctx.lambdaExpression())
            else -> throw IllegalArgumentException("Unknown primary expression: ${ctx.text}")
        }
    }

    // Literals
    override fun visitLiteral(ctx: FunkParser.LiteralContext): Any {
        return when {
            ctx.Integer() != null -> {
                val value = ctx.Integer().text.toLong()
                IntLiteral(value)
            }
            ctx.Float() != null -> {
                val value = ctx.Float().text.toDouble()
                FloatLiteral(value)
            }
            ctx.String() != null -> {
                val value = ctx.String().text.removeSurrounding("\"")
                StringLiteral(value)
            }
            ctx.Boolean() != null -> {
                val value = ctx.Boolean().text.toBoolean()
                BooleanLiteral(value)
            }
            else -> throw IllegalArgumentException("Unknown literal: ${ctx.text}")
        }
    }

    // Records
    override fun visitRecordLiteral(ctx: FunkParser.RecordLiteralContext): Any {
        val entries = ctx.recordEntry().map { visit(it) as RecordEntry }
        val restField = ctx.Identifier()?.text
        return RecordLiteral(entries, restField)
    }

    override fun visitRecordEntry(ctx: FunkParser.RecordEntryContext): Any {
        val name = ctx.Identifier().text
        val value = ctx.expression()?.let { visit(it) as Expression }
        return RecordEntry(name, value)
    }

    // Lambda expressions
    override fun visitLambdaExpression(ctx: FunkParser.LambdaExpressionContext): Any {
        val body = visit(ctx.expression()) as Expression
        
        return if (ctx.recordType() != null) {
            val recordType = visit(ctx.recordType()) as RecordTypeAnnotation
            val parameters = TypedParameters(recordType)
            LambdaExpression(parameters, body)
        } else {
            val params = ctx.untypedRecordParams().untypedParam().map { it.Identifier().text }
            val parameters = UntypedParameters(params)
            LambdaExpression(parameters, body)
        }
    }

    // Control flow
    override fun visitDoExpression(ctx: FunkParser.DoExpressionContext): Any {
        val statements = ctx.doStatement().map { visit(it) as Statement }
        val result = visit(ctx.expression()) as Expression
        return DoExpression(statements, result)
    }

    override fun visitDoStatement(ctx: FunkParser.DoStatementContext): Any {
        return when {
            ctx.letBinding() != null -> {
                val letBinding = visit(ctx.letBinding()) as LetBinding
                LetStatement(letBinding.variableName, letBinding.typeAnnotation, letBinding.expression)
            }
            ctx.resumeStatement() != null -> visit(ctx.resumeStatement())
            ctx.expressionStatement() != null -> visit(ctx.expressionStatement())
            else -> throw IllegalArgumentException("Unknown do statement: ${ctx.text}")
        }
    }

    // Patterns
    override fun visitPattern(ctx: FunkParser.PatternContext): Any {
        return when {
            ctx.WILDCARD() != null -> WildcardPattern()
            ctx.Identifier() != null && ctx.recordPattern() == null -> {
                val identifier = ctx.Identifier().text
                if (identifier.first().isUpperCase()) {
                    // Uppercase identifiers are constructor patterns (e.g., Red, Blue)
                    ConstructorPattern(identifier, null)
                } else {
                    // Lowercase identifiers are variable patterns (e.g., x, val)
                    VariablePattern(identifier)
                }
            }
            ctx.Identifier() != null && ctx.recordPattern() != null -> {
                val constructor = ctx.Identifier().text
                val recordPattern = visit(ctx.recordPattern()) as RecordPattern
                ConstructorPattern(constructor, recordPattern)
            }
            ctx.recordPattern() != null -> visit(ctx.recordPattern())
            else -> throw IllegalArgumentException("Unknown pattern: ${ctx.text}")
        }
    }

    override fun visitRecordPattern(ctx: FunkParser.RecordPatternContext): Any {
        val entries = ctx.recordPatternEntry().map { visit(it) as RecordPatternEntry }
        return RecordPattern(entries)
    }

    override fun visitRecordPatternEntry(ctx: FunkParser.RecordPatternEntryContext): Any {
        val name = ctx.Identifier().text
        val pattern = ctx.pattern()?.let { visit(it) as Pattern }
        return RecordPatternEntry(name, pattern)
    }

    // Type annotations
    override fun visitTypeAnnotation(ctx: FunkParser.TypeAnnotationContext): Any {
        return when {
            ctx.Identifier() != null -> {
                val constructor = ctx.Identifier().text
                val args = ctx.typeAnnotation().map { visit(it) as TypeAnnotation }
                if (args.isEmpty()) {
                    IdentifierTypeAnnotation(constructor)
                } else {
                    TypeApplicationAnnotation(constructor, args)
                }
            }
            ctx.TypeVariable() != null -> IdentifierTypeAnnotation(ctx.TypeVariable().text)
            ctx.recordType() != null -> visit(ctx.recordType())
            ctx.variantType() != null -> visit(ctx.variantType())
            ctx.functionType() != null -> visit(ctx.functionType())
            else -> throw IllegalArgumentException("Unknown type annotation: ${ctx.text}")
        }
    }

    override fun visitRecordType(ctx: FunkParser.RecordTypeContext): Any {
        val entries = ctx.recordTypeEntry().map { visit(it) as RecordTypeEntry }
        val hasEllipsis = ctx.ELLIPSIS() != null
        return RecordTypeAnnotation(entries, if (hasEllipsis) "..." else null)
    }

    override fun visitRecordTypeEntry(ctx: FunkParser.RecordTypeEntryContext): Any {
        val name = ctx.Identifier().text
        val type = visit(ctx.typeAnnotation()) as TypeAnnotation
        return RecordTypeEntry(name, type)
    }


    override fun visitFunctionType(ctx: FunkParser.FunctionTypeContext): Any {
        val paramType = visit(ctx.recordType()) as TypeAnnotation
        val returnType = visit(ctx.typeAnnotation()) as TypeAnnotation
        return FunctionTypeAnnotation(paramType, returnType)
    }
    
    /**
     * Substitute placeholders (@) in an expression with the given value expression.
     * This implements pipe operator desugaring.
     */
    private fun substitutePlaceholdersInExpression(expr: Expression, valueExpr: Expression): Expression {
        return when (expr) {
            is PlaceholderExpression -> {
                // Replace placeholder with the piped value
                valueExpr
            }
            is FunctionCall -> {
                // Recursively substitute in function calls
                FunctionCall(
                    substitutePlaceholdersInExpression(expr.function, valueExpr),
                    substitutePlaceholdersInExpression(expr.arguments, valueExpr),
                    expr.inferredType
                )
            }
            is RecordLiteral -> {
                // Recursively substitute in record entries
                val newEntries = expr.entries.map { entry ->
                    RecordEntry(
                        entry.name,
                        entry.value?.let { substitutePlaceholdersInExpression(it, valueExpr) }
                    )
                }
                RecordLiteral(newEntries, expr.restField, expr.inferredType)
            }
            is BinaryOperation -> {
                // Recursively substitute in binary operations
                BinaryOperation(
                    substitutePlaceholdersInExpression(expr.left, valueExpr),
                    expr.operator,
                    substitutePlaceholdersInExpression(expr.right, valueExpr),
                    expr.inferredType
                )
            }
            is MemberAccess -> {
                // Recursively substitute in member access
                MemberAccess(
                    substitutePlaceholdersInExpression(expr.receiver, valueExpr),
                    expr.member,
                    expr.inferredType
                )
            }
            // For other expressions, return as-is (no placeholders to substitute)
            else -> expr
        }
    }
}