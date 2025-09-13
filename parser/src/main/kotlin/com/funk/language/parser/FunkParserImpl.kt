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
            ctx.effectDeclaration() != null -> visit(ctx.effectDeclaration())
            ctx.implDeclaration() != null -> visit(ctx.implDeclaration())
            ctx.abilityDeclaration() != null -> visit(ctx.abilityDeclaration())
            ctx.implAbilityDeclaration() != null -> visit(ctx.implAbilityDeclaration())
            ctx.expressionStatement() != null -> visit(ctx.expressionStatement())
            else -> throw IllegalArgumentException("Unknown statement: ${ctx.text}")
        }
    }

    override fun visitExpressionStatement(ctx: FunkParser.ExpressionStatementContext): Any {
        // Expression statements are standalone expressions that should be evaluated
        val expression = visit(ctx.expression()) as Expression
        return TopLevelExpression(expression)
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
    
    override fun visitEffectDeclaration(ctx: FunkParser.EffectDeclarationContext): Any {
        val name = ctx.Identifier().text
        val operations = ctx.effectOperation().map { visit(it) as EffectOperation }
        return EffectDeclaration(name, operations)
    }
    
    override fun visitEffectOperation(ctx: FunkParser.EffectOperationContext): Any {
        val name = ctx.Identifier().text
        val paramType = visit(ctx.recordType()) as TypeAnnotation
        val returnType = visit(ctx.typeAnnotation()) as TypeAnnotation
        return EffectOperation(name, paramType, returnType)
    }
    
    override fun visitImplDeclaration(ctx: FunkParser.ImplDeclarationContext): Any {
        // New syntax: impl EffectName for HandlerName {params?} where {}
        val effectName = ctx.Identifier(0).text    // First identifier is effect name
        val handlerName = ctx.Identifier(1).text   // Second identifier is handler name (after FOR)
        val handlerParams = ctx.implParams()?.let { visit(it) as RecordTypeAnnotation }
        
        val operations = ctx.implOperation().map { visit(it) as ImplOperation }
        return ImplDeclaration(effectName, handlerName, handlerParams, operations)
    }
    
    override fun visitImplParams(ctx: FunkParser.ImplParamsContext): Any {
        val entries = ctx.recordTypeEntry().map { entry ->
            val name = entry.Identifier().text
            val type = visit(entry.typeAnnotation()) as TypeAnnotation
            RecordTypeEntry(name, type)
        }
        return RecordTypeAnnotation(entries, null) // No rest field for handler params
    }
    
    override fun visitImplOperation(ctx: FunkParser.ImplOperationContext): Any {
        val name = ctx.Identifier().text
        val paramType = visit(ctx.recordType()) as TypeAnnotation
        val returnType = visit(ctx.typeAnnotation()) as TypeAnnotation
        val implementation = visit(ctx.expression()) as Expression
        return ImplOperation(name, paramType, returnType, implementation)
    }

    override fun visitAbilityDeclaration(ctx: FunkParser.AbilityDeclarationContext): Any {
        val name = ctx.Identifier().text
        val typeParams = ctx.TypeVariable().map { it.text }
        val constraints = ctx.abilityConstraints()?.abilityConstraint()?.map { 
            visit(it) as AbilityConstraint 
        } ?: emptyList()
        val operations = ctx.abilityOperation().map { visit(it) as AbilityOperation }
        return AbilityDeclaration(name, typeParams, constraints, operations)
    }

    override fun visitAbilityConstraint(ctx: FunkParser.AbilityConstraintContext): Any {
        val abilityName = ctx.Identifier().text
        val typeParam = ctx.TypeVariable().text
        return AbilityConstraint(abilityName, typeParam)
    }

    override fun visitAbilityOperation(ctx: FunkParser.AbilityOperationContext): Any {
        val name = ctx.Identifier().text
        val paramType = visit(ctx.recordType()) as TypeAnnotation
        val returnType = visit(ctx.typeAnnotation()) as TypeAnnotation
        return AbilityOperation(name, paramType, returnType)
    }

    override fun visitImplAbilityDeclaration(ctx: FunkParser.ImplAbilityDeclarationContext): Any {
        val identifiers = ctx.Identifier()
        val abilityName = identifiers[0].text
        val targetTypes = identifiers.drop(1).map { 
            IdentifierTypeAnnotation(it.text) as TypeAnnotation 
        }
        val operations = ctx.implAbilityOperation().map { visit(it) as ImplAbilityOperation }
        return ImplAbilityDeclaration(abilityName, targetTypes, operations)
    }

    override fun visitImplAbilityOperation(ctx: FunkParser.ImplAbilityOperationContext): Any {
        val name = ctx.Identifier().text
        val paramType = visit(ctx.recordType()) as TypeAnnotation
        val returnType = visit(ctx.typeAnnotation()) as TypeAnnotation
        val implementation = visit(ctx.expression()) as Expression
        return ImplAbilityOperation(name, paramType, returnType, implementation)
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
        val name = ctx.Identifier().text
        val dataType = ctx.recordType()?.let { visit(it) as TypeAnnotation }
        return VariantConstructorDef(name, dataType)
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
        return visit(ctx.ifExpression())
    }

    override fun visitMatchCase(ctx: FunkParser.MatchCaseContext): Any {
        val pattern = visit(ctx.pattern()) as Pattern
        val expression = visit(ctx.expression()) as Expression
        return MatchCase(pattern, expression)
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
        return visit(ctx.comparisonExpression())
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
        var result = visit(ctx.unaryExpression(0)) as Expression
        
        for (i in 1 until ctx.unaryExpression().size) {
            val right = visit(ctx.unaryExpression(i)) as Expression
            val operator = when {
                ctx.MULTIPLY(i-1) != null -> "*"
                ctx.DIVIDE(i-1) != null -> "/"
                else -> "*"
            }
            result = BinaryOperation(result, operator, right)
        }
        
        return result
    }

    override fun visitUnaryExpression(ctx: FunkParser.UnaryExpressionContext): Any {
        return if (ctx.MINUS() != null) {
            val operand = visit(ctx.memberAccessExpression()) as Expression
            UnaryOperation("-", operand)
        } else {
            visit(ctx.memberAccessExpression()) as Expression
        }
    }

    override fun visitMemberAccessExpression(ctx: FunkParser.MemberAccessExpressionContext): Any {
        var result = visit(ctx.primaryExpression()) as Expression
        
        ctx.Identifier().forEach { identifier ->
            result = MemberAccess(result, identifier.text)
        }
        
        // Check if this is a function call (member access followed by record literal)
        if (ctx.recordLiteral() != null) {
            val arguments = visit(ctx.recordLiteral()) as Expression
            result = FunctionCall(result, arguments)
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
                // Check if this is a function call or record literal/update
                val recordExpr = visit(ctx.recordLiteral()) as Expression
                if (ctx.primaryExpression() != null) {
                    val primaryExpr = visit(ctx.primaryExpression()) as Expression
                    
                    // Only function calls and variant construction work with RecordLiteral, not RecordUpdateExpression
                    if (recordExpr is RecordLiteral) {
                        // Disambiguate between function call and variant construction
                        if (primaryExpr is Identifier && primaryExpr.name.first().isUpperCase()) {
                            // Uppercase identifier + record = variant construction (e.g., Blue {val = 10})
                            VariantConstruction(primaryExpr.name, recordExpr)
                        } else {
                            // Lowercase identifier + record = function call (e.g., func {x = 1})
                            FunctionCall(primaryExpr, recordExpr)
                        }
                    } else {
                        // RecordUpdateExpression cannot be used in function calls or variant construction
                        throw IllegalArgumentException("Record update expressions cannot be used in function calls or variant construction")
                    }
                } else {
                    recordExpr
                }
            }
            ctx.doExpression() != null -> visit(ctx.doExpression())
            ctx.lambdaExpression() != null -> visit(ctx.lambdaExpression())
            ctx.performExpression() != null -> visit(ctx.performExpression())
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
        // Check if this is a record update syntax: { expr | field = value, ... }
        if (ctx.expression() != null && ctx.PIPE_DELIM() != null) {
            val baseRecord = visit(ctx.expression()) as Expression
            val updates = ctx.recordUpdateEntry().map { updateEntry ->
                val fieldPath = if (updateEntry.DOT() != null) {
                    // Nested field update: y.z = v
                    listOf(updateEntry.Identifier(0).text, updateEntry.Identifier(1).text)
                } else {
                    // Simple field update: x = v
                    listOf(updateEntry.Identifier(0).text)
                }
                val value = visit(updateEntry.expression()) as Expression
                RecordUpdateEntry(fieldPath, value)
            }
            return RecordUpdateExpression(baseRecord, updates)
        } else {
            // Regular record literal: { field = value, ... }
            val entries = ctx.recordEntry().map { visit(it) as RecordEntry }
            val hasEllipsis = ctx.ELLIPSIS() != null
            return RecordLiteral(entries, if (hasEllipsis) "..." else null)
        }
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
            ctx.expressionStatement() != null -> {
                val topLevelExpr = visit(ctx.expressionStatement()) as TopLevelExpression
                ExpressionStatement(topLevelExpr.expression)
            }
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
        val effects = ctx.effectRow()?.let { visit(it) as EffectRowAnnotation }
        return FunctionTypeAnnotation(paramType, returnType, effects)
    }
    
    override fun visitEffectRow(ctx: FunkParser.EffectRowContext): Any {
        val effects = ctx.Identifier().map { it.text }
        return EffectRowAnnotation(effects)
    }
    
    override fun visitPerformExpression(ctx: FunkParser.PerformExpressionContext): Any {
        val expression = visit(ctx.expression()) as Expression
        val handlers = ctx.performHandler().map { handlerCtx ->
            val name = handlerCtx.Identifier().text
            val parameters = handlerCtx.recordLiteral()?.let { visit(it) as RecordLiteral }
            PerformHandler(name, parameters)
        }
        return PerformExpression(expression, handlers)
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