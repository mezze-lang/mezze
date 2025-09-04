package com.funk.language.parser

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

private class FunkASTBuilder : FunkBaseVisitor<ASTNode>() {

    override fun visitProgram(ctx: FunkParser.ProgramContext): ASTNode {
        val items = ctx.statement().mapNotNull { visit(it) as? ProgramItem }
        return Program(items)
    }

    // Declarations
    override fun visitLetBinding(ctx: FunkParser.LetBindingContext): ASTNode {
        val variableName = ctx.Identifier().text
        val typeAnnotation = ctx.typeAnnotation()?.let { visit(it) as TypeAnnotation }
        val expression = visit(ctx.expression()) as Expression
        return LetBinding(variableName, typeAnnotation, expression)
    }

    override fun visitTypeDeclaration(ctx: FunkParser.TypeDeclarationContext): ASTNode {
        val name = ctx.Identifier().text
        val typeParams = ctx.typeParams()?.Identifier()?.map { it.text } ?: emptyList()
        val definition = visit(ctx.typeDefinition()) as TypeDefinition
        return TypeDeclaration(name, typeParams, definition)
    }

    override fun visitVariantType(ctx: FunkParser.VariantTypeContext): ASTNode {
        val constructors = ctx.variantConstructor().map { visit(it) as VariantConstructor }
        val isOpen = ctx.ELLIPSIS() != null
        return VariantType(constructors, isOpen)
    }

    override fun visitVariantConstructor(ctx: FunkParser.VariantConstructorContext): ASTNode {
        val nameIdentifier = visit(ctx.qualifiedIdentifier()) as Identifier
        val recordType = ctx.recordType()?.let { visit(it) as RecordType }
        return VariantConstructor(nameIdentifier.name, recordType)
    }

    override fun visitQualifiedIdentifier(ctx: FunkParser.QualifiedIdentifierContext): ASTNode {
        // Return a simple identifier for now, but join the parts with dots
        val qualifiedName = ctx.Identifier().joinToString(".") { it.text }
        return Identifier(qualifiedName)
    }

    override fun visitEffectDeclaration(ctx: FunkParser.EffectDeclarationContext): ASTNode {
        val name = ctx.Identifier().text
        val inputType = visit(ctx.recordType()) as RecordType
        val resultType = visit(ctx.variantType()) as VariantType
        return EffectDeclaration(name, inputType, resultType)
    }

    override fun visitAbilityDeclaration(ctx: FunkParser.AbilityDeclarationContext): ASTNode {
        val name = ctx.Identifier().text
        val effects = ctx.effectSignature().map { visit(it) as EffectSignature }
        return AbilityDeclaration(name, effects)
    }

    override fun visitEffectSignature(ctx: FunkParser.EffectSignatureContext): ASTNode {
        val name = ctx.Identifier().text
        val inputType = visit(ctx.recordType()) as RecordType
        val resultType = visit(ctx.variantType()) as VariantType
        return EffectSignature(name, inputType, resultType)
    }

    override fun visitModuleDeclaration(ctx: FunkParser.ModuleDeclarationContext): ASTNode {
        val name = ctx.Identifier().text
        return ModuleDeclaration(name, emptyList()) // Simplified for now
    }

    override fun visitExposeDeclaration(ctx: FunkParser.ExposeDeclarationContext): ASTNode {
        val items = ctx.exposeList().exposeItem().map { visit(it) as ExposeItem }
        return ExposeDeclaration(items)
    }

    override fun visitExposeItem(ctx: FunkParser.ExposeItemContext): ASTNode {
        val kind = when {
            ctx.TYPE() != null -> ExposeKind.TYPE
            ctx.EFFECT() != null -> ExposeKind.EFFECT
            ctx.ABILITY() != null -> ExposeKind.ABILITY
            else -> null
        }
        val name = ctx.Identifier().text
        return ExposeItem(kind, name)
    }

    override fun visitAccessDeclaration(ctx: FunkParser.AccessDeclarationContext): ASTNode {
        val moduleName = ctx.Identifier().text
        val items = ctx.accessList().accessItem().map { visit(it) as AccessItem }
        return AccessDeclaration(moduleName, items)
    }

    override fun visitAccessItem(ctx: FunkParser.AccessItemContext): ASTNode {
        val name = ctx.Identifier(0).text
        val alias = ctx.Identifier(1)?.text
        return AccessItem(name, alias)
    }

    // Expressions
    override fun visitExpression(ctx: FunkParser.ExpressionContext): ASTNode {
        return visit(ctx.pipeExpression())
    }

    override fun visitPipeExpression(ctx: FunkParser.PipeExpressionContext): ASTNode {
        var result = visit(ctx.matchExpression(0)) as Expression
        
        for (i in 1 until ctx.matchExpression().size) {
            val right = visit(ctx.matchExpression(i)) as Expression
            result = PipeExpression(result, right)
        }
        
        return result
    }

    override fun visitMatchExpression(ctx: FunkParser.MatchExpressionContext): ASTNode {
        return if (ctx.MATCH() != null) {
            val expression = visit(ctx.expression()) as Expression
            val cases = ctx.matchCase().map { visit(it) as MatchCase }
            MatchExpression(expression, cases)
        } else {
            visit(ctx.handleExpression())
        }
    }

    override fun visitMatchCase(ctx: FunkParser.MatchCaseContext): ASTNode {
        val pattern = visit(ctx.pattern()) as Pattern
        val expression = visit(ctx.expression()) as Expression
        return MatchCase(pattern, expression)
    }

    override fun visitPattern(ctx: FunkParser.PatternContext): ASTNode {
        return when {
            ctx.WILDCARD() != null -> WildcardPattern
            ctx.Identifier() != null -> {
                val identifier = ctx.Identifier().text
                val recordPattern = ctx.recordPattern()?.let { visit(it) as RecordPattern }
                
                // Use capitalization convention: uppercase = constructor, lowercase = variable
                if (identifier.first().isUpperCase()) {
                    ConstructorPattern(identifier, recordPattern)
                } else {
                    if (recordPattern != null) {
                        throw IllegalArgumentException("Variable patterns cannot have record patterns: $identifier")
                    }
                    VariablePattern(identifier)
                }
            }
            ctx.recordPattern() != null -> visit(ctx.recordPattern())
            else -> throw IllegalArgumentException("Unknown pattern type")
        }
    }

    override fun visitRecordPattern(ctx: FunkParser.RecordPatternContext): ASTNode {
        val entries = ctx.recordPatternEntry().map { visit(it) as RecordPatternEntry }
        return RecordPattern(entries)
    }

    override fun visitRecordPatternEntry(ctx: FunkParser.RecordPatternEntryContext): ASTNode {
        val name = ctx.Identifier().text
        val pattern = ctx.pattern()?.let { visit(it) as Pattern }
        return RecordPatternEntry(name, pattern)
    }

    override fun visitHandleExpression(ctx: FunkParser.HandleExpressionContext): ASTNode {
        return if (ctx.HANDLE() != null) {
            val expression = visit(ctx.expression()) as Expression
            val cases = ctx.handleCase().map { visit(it) as HandleCase }
            val withClause = ctx.recordLiteral()?.let { visit(it) as RecordLiteral }
            HandleExpression(expression, cases, withClause)
        } else {
            visit(ctx.ifExpression())
        }
    }

    override fun visitHandleCase(ctx: FunkParser.HandleCaseContext): ASTNode {
        val effectName = ctx.Identifier().text
        val pattern = ctx.recordPattern()?.let { visit(it) as RecordPattern }
        val expression = visit(ctx.expression()) as Expression
        
        // Convert expression to statements and result
        val (statements, result) = when (expression) {
            is DoExpression -> expression.statements to expression.result
            else -> emptyList<Statement>() to expression
        }
        
        return HandleCase(effectName, pattern, statements, result)
    }

    override fun visitIfExpression(ctx: FunkParser.IfExpressionContext): ASTNode {
        return if (ctx.IF() != null) {
            val condition = visit(ctx.expression(0)) as Expression
            val thenBranch = visit(ctx.expression(1)) as Expression
            val elseBranch = visit(ctx.expression(2)) as Expression
            IfExpression(condition, thenBranch, elseBranch)
        } else {
            visit(ctx.updateExpression())
        }
    }

    override fun visitUpdateExpression(ctx: FunkParser.UpdateExpressionContext): ASTNode {
        val baseExpr = visit(ctx.additiveExpression()) as Expression
        return if (ctx.recordLiteral() != null) {
            val updates = visit(ctx.recordLiteral()) as RecordLiteral
            RecordUpdate(baseExpr, updates.entries)
        } else {
            baseExpr
        }
    }

    override fun visitAdditiveExpression(ctx: FunkParser.AdditiveExpressionContext): ASTNode {
        var left = visit(ctx.multiplicativeExpression(0)) as Expression
        
        for (i in 1 until ctx.multiplicativeExpression().size) {
            val operator = ctx.getChild(i * 2 - 1).text
            val right = visit(ctx.multiplicativeExpression(i)) as Expression
            left = BinaryOperation(left, operator, right)
        }
        
        return left
    }

    override fun visitMultiplicativeExpression(ctx: FunkParser.MultiplicativeExpressionContext): ASTNode {
        var left = visit(ctx.memberAccessExpression(0)) as Expression
        
        for (i in 1 until ctx.memberAccessExpression().size) {
            val operator = ctx.getChild(i * 2 - 1).text
            val right = visit(ctx.memberAccessExpression(i)) as Expression
            left = BinaryOperation(left, operator, right)
        }
        
        return left
    }

    override fun visitMemberAccessExpression(ctx: FunkParser.MemberAccessExpressionContext): ASTNode {
        var receiver = visit(ctx.primaryExpression()) as Expression
        
        ctx.Identifier().forEach { identifier ->
            receiver = MemberAccess(receiver, identifier.text)
        }
        
        return receiver
    }

    override fun visitPrimaryExpression(ctx: FunkParser.PrimaryExpressionContext): ASTNode {
        // Handle function calls first
        if (ctx.primaryExpression() != null && ctx.recordLiteral() != null) {
            val function = visit(ctx.primaryExpression()) as Expression
            val arguments = visit(ctx.recordLiteral()) as RecordLiteral
            return FunctionCall(function, arguments)
        }

        return when {
            ctx.literal() != null -> visit(ctx.literal())
            ctx.Identifier() != null -> Identifier(ctx.Identifier().text)
            ctx.PLACEHOLDER() != null -> PlaceholderExpression
            ctx.expression() != null -> visit(ctx.expression()) // Parenthesized
            ctx.recordLiteral() != null -> visit(ctx.recordLiteral())
            ctx.blockExpression() != null -> visit(ctx.blockExpression())
            ctx.doExpression() != null -> visit(ctx.doExpression())
            ctx.lambdaExpression() != null -> visit(ctx.lambdaExpression())
            else -> throw IllegalArgumentException("Unknown primary expression: ${ctx.text}")
        }
    }

    override fun visitLambdaExpression(ctx: FunkParser.LambdaExpressionContext): ASTNode {
        val parameters = if (ctx.recordType() != null) {
            TypedParameters(visit(ctx.recordType()) as RecordType)
        } else {
            val untypedParams = visit(ctx.untypedRecordParams()) as UntypedParameters
            untypedParams
        }
        val body = visit(ctx.expression()) as Expression
        return LambdaExpression(parameters, body)
    }

    override fun visitUntypedRecordParams(ctx: FunkParser.UntypedRecordParamsContext): ASTNode {
        val params = ctx.untypedParam().map { 
            val paramNode = visit(it)
            when (paramNode) {
                is String -> paramNode
                else -> (paramNode as Identifier).name
            }
        }
        return UntypedParameters(params)
    }

    override fun visitUntypedParam(ctx: FunkParser.UntypedParamContext): ASTNode {
        return Identifier(ctx.Identifier().text)
    }

    override fun visitBlockExpression(ctx: FunkParser.BlockExpressionContext): ASTNode {
        val statements = ctx.statement().map { visit(it) as Statement }
        val result = ctx.expression()?.let { visit(it) as Expression } 
            ?: throw IllegalArgumentException("Block expression must have a final expression")
        return BlockExpression(statements, result)
    }

    override fun visitDoExpression(ctx: FunkParser.DoExpressionContext): ASTNode {
        val statements = ctx.doStatement().map { visit(it) as Statement }
        val result = ctx.expression()?.let { visit(it) as Expression } 
            ?: throw IllegalArgumentException("Do expression must have a final expression")
        return DoExpression(statements, result)
    }

    override fun visitDoStatement(ctx: FunkParser.DoStatementContext): ASTNode {
        return when {
            ctx.letBinding() != null -> visit(ctx.letBinding())
            ctx.resumeStatement() != null -> visit(ctx.resumeStatement())
            ctx.expressionStatement() != null -> visit(ctx.expressionStatement())
            else -> throw IllegalArgumentException("Unknown do statement type")
        }
    }

    override fun visitRecordLiteral(ctx: FunkParser.RecordLiteralContext): ASTNode {
        val entries = ctx.recordEntry().map { visit(it) as RecordEntry }
        val restField = ctx.Identifier()?.text
        return RecordLiteral(entries, restField)
    }

    override fun visitRecordEntry(ctx: FunkParser.RecordEntryContext): ASTNode {
        val name = ctx.Identifier().text
        val value = ctx.expression()?.let { visit(it) as Expression }
        return RecordEntry(name, value)
    }

    override fun visitRecordType(ctx: FunkParser.RecordTypeContext): ASTNode {
        val entries = ctx.recordTypeEntry().map { visit(it) as RecordTypeEntry }
        val restField = ctx.Identifier()?.text
        return RecordType(entries, restField)
    }

    override fun visitRecordTypeEntry(ctx: FunkParser.RecordTypeEntryContext): ASTNode {
        val name = ctx.Identifier().text
        val type = visit(ctx.typeAnnotation()) as TypeAnnotation
        return RecordTypeEntry(name, type)
    }

    override fun visitTypeAnnotation(ctx: FunkParser.TypeAnnotationContext): ASTNode {
        return when {
            ctx.Identifier() != null -> SimpleType(ctx.Identifier().text)
            ctx.recordType() != null -> visit(ctx.recordType())
            ctx.variantType() != null -> visit(ctx.variantType())
            ctx.functionType() != null -> visit(ctx.functionType())
            ctx.effectList() != null -> {
                val baseType = visit(ctx.typeAnnotation()) as TypeAnnotation
                val effects = ctx.effectList().Identifier().map { it.text }
                // This is a simplified representation - would need proper function type parsing
                baseType
            }
            else -> throw IllegalArgumentException("Unknown type annotation")
        }
    }

    override fun visitFunctionType(ctx: FunkParser.FunctionTypeContext): ASTNode {
        val paramType = visit(ctx.recordType()) as RecordType
        val returnType = visit(ctx.variantType()) as VariantType
        val effects = ctx.effectList().Identifier()?.map { it.text } ?: emptyList()
        return FunctionType(paramType, returnType, effects)
    }

    override fun visitLiteral(ctx: FunkParser.LiteralContext): ASTNode {
        return when {
            ctx.Integer() != null -> Literal(ctx.Integer().text.toInt())
            ctx.Float() != null -> Literal(ctx.Float().text.toFloat())
            ctx.String() != null -> Literal(ctx.String().text.removeSurrounding("\""))
            ctx.Boolean() != null -> Literal(ctx.Boolean().text.toBoolean())
            else -> throw IllegalArgumentException("Unknown literal type")
        }
    }

    override fun visitResumeStatement(ctx: FunkParser.ResumeStatementContext): ASTNode {
        val expression = visit(ctx.expression()) as Expression
        return ResumeStatement(expression)
    }

    override fun visitExpressionStatement(ctx: FunkParser.ExpressionStatementContext): ASTNode {
        return ExpressionStatement(visit(ctx.expression()) as Expression)
    }
}