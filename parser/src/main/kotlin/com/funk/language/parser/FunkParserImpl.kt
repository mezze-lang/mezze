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
     * Extract source location information from ANTLR4 parse context
     */
    private fun getSourceLocation(ctx: org.antlr.v4.runtime.ParserRuleContext): SourceLocation {
        val token = ctx.start
        return SourceLocation(
            line = token.line,
            column = token.charPositionInLine + 1, // ANTLR uses 0-based columns, we want 1-based
            file = null // File information could be added later if needed
        )
    }

    /**
     * Desugar binary operations to function calls with parenthesized operator names
     */
    private fun createBinaryOperationCall(
        left: Expression,
        operator: String,
        right: Expression,
        sourceLocation: SourceLocation? = null
    ): FunctionCall {
        return FunctionCall(
            function = Identifier("($operator)", sourceLocation = sourceLocation),
            arguments = RecordLiteral(
                entries = listOf(
                    RecordFieldEntry("left", left),
                    RecordFieldEntry("right", right)
                )
            ),
            sourceLocation = sourceLocation
        )
    }

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
    
    /**
     * Check if an identifier is a built-in constructor
     */
    private fun isBuiltinConstructor(name: String): Boolean {
        return name in setOf("Some", "None", "Ok", "Err", "LT", "EQ", "GT")
    }
    
    /**
     * Check if an operator is a built-in operator that should be parsed specially
     */
    private fun isBuiltinOperator(operator: String): Boolean {
        return operator in setOf(
            "+", "-", "*", "/", "%",           // Arithmetic
            "==", "!=", "<", ">", "<=", ">=",  // Comparison  
            "&&", "||", "!",                   // Boolean
            "++"                               // String concatenation
        )
    }

    override fun visitProgram(ctx: FunkParser.ProgramContext): Any {
        val items = mutableListOf<ProgramItem>()
        
        // Process use declarations first
        ctx.useDeclaration()?.forEach { useCtx ->
            items.add(visit(useCtx) as UseDeclaration)
        }
        
        // Process pub use declarations
        ctx.pubUseDeclaration()?.forEach { pubUseCtx ->
            items.add(visit(pubUseCtx) as PubUseDeclaration)
        }
        
        // Process regular statements
        ctx.statement()?.forEach { stmtCtx ->
            val item = visit(stmtCtx) as? ProgramItem
            if (item != null) {
                items.add(item)
            }
        }
        
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
        val visibility = if (ctx.PUB() != null) Visibility.PUBLIC else Visibility.PRIVATE
        val typeAnnotation = ctx.typeAnnotation()?.let { visit(it) as TypeAnnotation }
        val expression = visit(ctx.expression()) as Expression
        return LetBinding(variableName, visibility, typeAnnotation, null, expression)
    }

    override fun visitTypeDeclaration(ctx: FunkParser.TypeDeclarationContext): Any {
        val name = ctx.Identifier().text
        val visibility = if (ctx.PUB() != null) Visibility.PUBLIC else Visibility.PRIVATE
        val typeParams = ctx.typeParams()?.TypeVariable()?.map { it.text } ?: emptyList()
        val definition = visit(ctx.typeDefinition()) as TypeDefinition
        return TypeDeclaration(name, visibility, typeParams, definition)
    }
    
    override fun visitEffectDeclaration(ctx: FunkParser.EffectDeclarationContext): Any {
        val name = ctx.Identifier().text
        val visibility = if (ctx.PUB() != null) Visibility.PUBLIC else Visibility.PRIVATE
        val operations = ctx.effectOperation().map { visit(it) as EffectOperation }
        return EffectDeclaration(name, visibility, operations)
    }
    
    override fun visitEffectOperation(ctx: FunkParser.EffectOperationContext): Any {
        val name = ctx.Identifier().text
        val paramType = visit(ctx.recordType()) as TypeAnnotation
        val returnType = visit(ctx.typeAnnotation()) as TypeAnnotation
        val effects = ctx.effectRow()?.let { effectRowCtx ->
            effectRowCtx.Identifier().map { it.text }
        } ?: emptyList()
        return EffectOperation(name, paramType, returnType, effects)
    }
    
    override fun visitImplDeclaration(ctx: FunkParser.ImplDeclarationContext): Any {
        // New syntax: impl EffectName for PerformerName {params?} where {}
        val effectName = ctx.Identifier(0).text    // First identifier is effect name
        val performerName = ctx.Identifier(1).text   // Second identifier is performer name (after FOR)
        val performerParams = ctx.performerParams()?.let { 
            ctx.performerParams()!!.performerParam().map { paramCtx ->
                val name = paramCtx.Identifier().text
                val type = visit(paramCtx.typeAnnotation()) as TypeAnnotation
                PerformerParameter(name, type)
            }
        } ?: emptyList()
        
        val operations = ctx.performerOperation().map { visit(it) as PerformerOperation }
        return ImplDeclaration(effectName, performerName, performerParams, operations)
    }
    
    
    override fun visitPerformerOperation(ctx: FunkParser.PerformerOperationContext): Any {
        val name = ctx.Identifier().text
        val paramType = visit(ctx.recordType()) as TypeAnnotation
        val returnType = visit(ctx.typeAnnotation()) as TypeAnnotation
        val implementation = visit(ctx.expression()) as Expression
        
        // Handle optional effect row
        val effectAnnotation = ctx.effectRow()?.let { effectRowCtx ->
            val effects = effectRowCtx.Identifier()?.map { it.text } ?: emptyList()
            EffectRowAnnotation(effects)
        }
        
        return PerformerOperation(name, paramType, returnType, implementation, effectAnnotation)
    }
    

    override fun visitAbilityDeclaration(ctx: FunkParser.AbilityDeclarationContext): Any {
        val name = ctx.Identifier().text
        val visibility = if (ctx.PUB() != null) Visibility.PUBLIC else Visibility.PRIVATE
        val typeParams = ctx.TypeVariable().map { it.text }
        val constraints = emptyList<AbilityConstraint>() // No longer used in new syntax
        val dependencies = ctx.abilityDependencies()?.abilityDependency()?.map {
            visit(it) as AbilityDependency
        } ?: emptyList()
        val operations = ctx.abilityOperation().map { visit(it) as AbilityOperation }
        return AbilityDeclaration(name, visibility, typeParams, constraints, dependencies, operations)
    }

    override fun visitAbilityConstraint(ctx: FunkParser.AbilityConstraintContext): Any {
        val abilityName = ctx.Identifier().text
        val typeParam = ctx.TypeVariable().text
        // This is used for function-level ability constraints, not ability declarations
        return FunkType.FunctionAbilityConstraint(abilityName, listOf(TypeVar(typeParam).let { FunkType.VarType(it) }))
    }
    
    override fun visitAbilityDependencies(ctx: FunkParser.AbilityDependenciesContext): Any {
        return ctx.abilityDependency().map { visit(it) as AbilityDependency }
    }
    
    override fun visitAbilityDependency(ctx: FunkParser.AbilityDependencyContext): Any {
        val abilityName = ctx.Identifier().text
        val typeArgs = ctx.typeAnnotation().map { visit(it) as TypeAnnotation }
        return AbilityDependency(abilityName, typeArgs)
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
    
    // Module system declarations
    override fun visitUseDeclaration(ctx: FunkParser.UseDeclarationContext): Any {
        val path = visit(ctx.usePath()) as UsePath
        val alias = ctx.Identifier()?.text
        return UseDeclaration(path, alias)
    }
    
    override fun visitPubUseDeclaration(ctx: FunkParser.PubUseDeclarationContext): Any {
        val path = visit(ctx.usePath()) as UsePath
        val alias = ctx.Identifier()?.text
        return PubUseDeclaration(path, alias)
    }
    
    override fun visitUsePath(ctx: FunkParser.UsePathContext): Any {
        return when {
            ctx.moduleSegments() != null && ctx.useList() == null -> {
                // Simple module path: math::geometry
                @Suppress("UNCHECKED_CAST")
                val segments = visit(ctx.moduleSegments()) as List<String>
                ModulePath(segments)
            }
            ctx.moduleSegments() != null && ctx.useList() != null -> {
                // Selective import: math::geometry::{area, Shape as alias}
                @Suppress("UNCHECKED_CAST")
                val module = visit(ctx.moduleSegments()) as List<String>
                @Suppress("UNCHECKED_CAST")
                val items = visit(ctx.useList()) as List<ImportItem>
                SelectivePath(module, items)
            }
            else -> throw IllegalArgumentException("Unknown use path: ${ctx.text}")
        }
    }
    
    override fun visitModuleSegments(ctx: FunkParser.ModuleSegmentsContext): Any {
        return ctx.Identifier().map { it.text }
    }
    
    override fun visitUseList(ctx: FunkParser.UseListContext): Any {
        return ctx.useItem().map { visit(it) as ImportItem }
    }
    
    override fun visitUseItem(ctx: FunkParser.UseItemContext): Any {
        val name = ctx.Identifier(0).text
        val alias = if (ctx.Identifier().size > 1) ctx.Identifier(1).text else null
        return ImportItem(name, alias)
    }
    
    override fun visitQualifiedIdentifier(ctx: FunkParser.QualifiedIdentifierContext): Any {
        val identifiers = ctx.Identifier().map { it.text }
        val sourceLocation = getSourceLocation(ctx)
        
        return if (identifiers.size == 1) {
            // Simple identifier
            val name = identifiers[0]
            // CRITICAL FIX: Convert uppercase identifiers to variant constructions
            if (name.first().isUpperCase()) {
                VariantConstruction(name, null, sourceLocation = sourceLocation)  // Variant constructor without arguments
            } else {
                Identifier(name, sourceLocation = sourceLocation)  // Regular identifier
            }
        } else {
            // Qualified identifier - join with ::
            val qualifiedName = identifiers.joinToString("::")
            Identifier(qualifiedName, sourceLocation = sourceLocation)
        }
    }

    override fun visitFunctionAbilityConstraints(ctx: FunkParser.FunctionAbilityConstraintsContext): Any {
        return ctx.functionAbilityConstraint().map { visit(it) as FunctionAbilityConstraint }
    }

    override fun visitFunctionAbilityConstraint(ctx: FunkParser.FunctionAbilityConstraintContext): Any {
        val abilityName = ctx.Identifier().text
        val typeArgs = ctx.typeAnnotation().map { visit(it) as TypeAnnotation }
        return FunctionAbilityConstraint(abilityName, typeArgs)
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
            result = substitutePlaceholdersInAST(right, result)
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
        val patterns = ctx.patternList().pattern().map { visit(it) as Pattern }
        val guard = ctx.guardCondition()?.expression()?.let { visit(it) as Expression }
        val expression = visit(ctx.expression()) as Expression
        return MatchCase(patterns, guard, expression)
    }

    override fun visitPatternList(ctx: FunkParser.PatternListContext): Any {
        return ctx.pattern().map { visit(it) as Pattern }
    }

    override fun visitGuardCondition(ctx: FunkParser.GuardConditionContext): Any {
        return visit(ctx.expression()) as Expression
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
        return visit(ctx.logicalOrExpression())
    }

    override fun visitLogicalOrExpression(ctx: FunkParser.LogicalOrExpressionContext): Any {
        var result = visit(ctx.logicalAndExpression(0)) as Expression
        val sourceLocation = getSourceLocation(ctx)
        
        for (i in 1 until ctx.logicalAndExpression().size) {
            val right = visit(ctx.logicalAndExpression(i)) as Expression
            // Desugar logical OR to function call
            result = createBinaryOperationCall(result, "||", right, sourceLocation)
        }
        
        return result
    }

    override fun visitLogicalAndExpression(ctx: FunkParser.LogicalAndExpressionContext): Any {
        var result = visit(ctx.comparisonExpression(0)) as Expression
        val sourceLocation = getSourceLocation(ctx)
        
        for (i in 1 until ctx.comparisonExpression().size) {
            val right = visit(ctx.comparisonExpression(i)) as Expression
            // Desugar logical AND to function call
            result = createBinaryOperationCall(result, "&&", right, sourceLocation)
        }
        
        return result
    }

    override fun visitComparisonExpression(ctx: FunkParser.ComparisonExpressionContext): Any {
        var result = visit(ctx.additiveExpression(0)) as Expression
        
        val sourceLocation = getSourceLocation(ctx)
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
            
            // Desugar comparison operators to function calls
            result = createBinaryOperationCall(result, operator, right, sourceLocation)
        }
        
        return result
    }

    override fun visitAdditiveExpression(ctx: FunkParser.AdditiveExpressionContext): Any {
        var result = visit(ctx.multiplicativeExpression(0)) as Expression
        
        val sourceLocation = getSourceLocation(ctx)
        for (i in 1 until ctx.multiplicativeExpression().size) {
            val right = visit(ctx.multiplicativeExpression(i)) as Expression
            val operator = when {
                ctx.PLUS(i-1) != null -> "+"
                ctx.MINUS(i-1) != null -> "-"
                ctx.CONCAT(i-1) != null -> "++"
                else -> "+"
            }
            
            // Desugar arithmetic operators to function calls
            result = createBinaryOperationCall(result, operator, right, sourceLocation)
        }
        
        return result
    }

    override fun visitMultiplicativeExpression(ctx: FunkParser.MultiplicativeExpressionContext): Any {
        var result = visit(ctx.unaryExpression(0)) as Expression
        
        val sourceLocation = getSourceLocation(ctx)
        for (i in 1 until ctx.unaryExpression().size) {
            val right = visit(ctx.unaryExpression(i)) as Expression
            val operator = when {
                ctx.MULTIPLY(i-1) != null -> "*"
                ctx.DIVIDE(i-1) != null -> "/"
                ctx.FRACTION(i-1) != null -> "//"
                ctx.MODULO(i-1) != null -> "%"
                else -> "*"
            }
            
            // Desugar multiplicative operators to function calls
            result = createBinaryOperationCall(result, operator, right, sourceLocation)
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
        
        // Handle member access and array access
        val identifiers = ctx.Identifier()
        val expressions = ctx.expression()
        
        var identifierIndex = 0
        var expressionIndex = 0
        
        // Process all access operations in order
        for (i in 0 until (identifiers.size + expressions.size)) {
            // Check which comes first in the source
            val nextIdentifier = if (identifierIndex < identifiers.size) identifiers[identifierIndex] else null
            val nextExpression = if (expressionIndex < expressions.size) expressions[expressionIndex] else null
            
            when {
                nextIdentifier != null && (nextExpression == null || 
                    nextIdentifier.symbol.startIndex < nextExpression.start.startIndex) -> {
                    // Member access: obj.field
                    result = MemberAccess(result, nextIdentifier.text)
                    identifierIndex++
                }
                nextExpression != null -> {
                    // Array access: obj[index]
                    val index = visit(nextExpression) as Expression
                    result = ArrayAccess(result, index)
                    expressionIndex++
                }
            }
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
            ctx.qualifiedIdentifier() != null -> {
                visit(ctx.qualifiedIdentifier())
            }
            ctx.PLACEHOLDER() != null -> {
                // Create placeholder expression for pipe operator desugaring
                val sourceLocation = getSourceLocation(ctx)
                PlaceholderExpression(sourceLocation = sourceLocation)
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
                        throw SpreadParseError.InvalidRecordUpdateContext
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
        val sourceLocation = getSourceLocation(ctx)
        return when {
            ctx.Integer() != null -> {
                val value = ctx.Integer().text.toLong()
                IntLiteral(value, sourceLocation = sourceLocation)
            }
            ctx.Float() != null -> {
                val value = ctx.Float().text.toDouble()
                FloatLiteral(value, sourceLocation = sourceLocation)
            }
            ctx.String() != null -> {
                val value = ctx.String().text.removeSurrounding("\"")
                StringLiteral(value, sourceLocation = sourceLocation)
            }
            ctx.Boolean() != null -> {
                val value = ctx.Boolean().text.toBoolean()
                BooleanLiteral(value, sourceLocation = sourceLocation)
            }
            ctx.arrayListLiteral() != null -> {
                visitArrayListLiteral(ctx.arrayListLiteral())
            }
            else -> throw IllegalArgumentException("Unknown literal: ${ctx.text}")
        }
    }

    // ArrayList literals
    override fun visitArrayListLiteral(ctx: FunkParser.ArrayListLiteralContext): Any {
        val sourceLocation = getSourceLocation(ctx)
        val elements = ctx.expression().map { visit(it) as Expression }
        return ArrayLiteral(elements, sourceLocation = sourceLocation)
    }

    // Records
    override fun visitRecordLiteral(ctx: FunkParser.RecordLiteralContext): Any {
        // Restrictive spread syntax: { ...record, field = value }
        val entries = ctx.recordLiteralEntry().map { visit(it) as RecordLiteralEntry }
        
        // Validate spread syntax restrictions
        validateSpreadSyntax(entries, ctx)
        
        return RecordLiteral(entries)
    }
    
    private fun validateSpreadSyntax(entries: List<RecordLiteralEntry>, ctx: FunkParser.RecordLiteralContext) {
        val spreadEntries = entries.filterIsInstance<RecordSpreadEntry>()
        val fieldEntries = entries.filterIsInstance<RecordFieldEntry>()
        
        // Rule 1: Only one spread expression allowed
        if (spreadEntries.size > 1) {
            throw SpreadParseError.MultipleSpreadExpressions
        }
        
        // Rule 2: Spread must appear before field entries (if both exist)
        if (spreadEntries.isNotEmpty() && fieldEntries.isNotEmpty()) {
            val spreadIndex = entries.indexOfFirst { it is RecordSpreadEntry }
            val firstFieldIndex = entries.indexOfFirst { it is RecordFieldEntry }
            
            if (spreadIndex > firstFieldIndex) {
                throw SpreadParseError.SpreadAfterFields
            }
        }
    }

    override fun visitRecordLiteralEntry(ctx: FunkParser.RecordLiteralEntryContext): Any {
        return when {
            ctx.ELLIPSIS() != null -> {
                // Spread syntax: ...record
                val expression = visit(ctx.expression()) as Expression
                RecordSpreadEntry(expression)
            }
            ctx.expression() != null -> {
                // Full syntax: name = value
                val name = ctx.Identifier().text
                val value = visit(ctx.expression()) as Expression
                RecordFieldEntry(name, value)
            }
            else -> {
                // Punned syntax: name (expands to name = name)
                val name = ctx.Identifier().text
                RecordFieldEntry(name, null)
            }
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
            
            // Check if this is the new syntax with explicit return type and constraints
            val returnType = ctx.typeAnnotation()?.let { visit(it) as TypeAnnotation }
            val abilityConstraints = ctx.functionAbilityConstraints()?.let {
                @Suppress("UNCHECKED_CAST")
                visit(it) as List<FunctionAbilityConstraint>
            } ?: emptyList()
            
            LambdaExpression(parameters, body, returnType, abilityConstraints)
        } else {
            val params = ctx.untypedRecordParams().untypedParam().map { it.Identifier().text }
            val hasEllipsis = ctx.untypedRecordParams().ELLIPSIS() != null
            val parameters = UntypedParameters(params, hasEllipsis)
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
            ctx.LPAREN() != null && ctx.typeAnnotation().isNotEmpty() -> {
                // Parenthesized type - just unwrap it
                visit(ctx.typeAnnotation(0)) as TypeAnnotation
            }
            ctx.recordType() != null -> visit(ctx.recordType())
            ctx.variantType() != null -> visit(ctx.variantType())
            ctx.functionType() != null -> visit(ctx.functionType())
            ctx.arrayListType() != null -> visit(ctx.arrayListType())
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
        val paramType = if (ctx.recordType() != null) {
            visit(ctx.recordType()) as TypeAnnotation
        } else {
            // Shorthand syntax: 'a -> 'b
            visit(ctx.simpleType()) as TypeAnnotation
        }
        val returnType = visit(ctx.typeAnnotation()) as TypeAnnotation
        val effects = ctx.effectRow()?.let { visit(it) as EffectRowAnnotation }

        // Collect ability constraints from both positions (before and after arrow)
        val allConstraints = mutableListOf<FunctionAbilityConstraint>()
        ctx.functionAbilityConstraints().forEach { constraintsCtx ->
            @Suppress("UNCHECKED_CAST")
            val constraints = visit(constraintsCtx) as List<FunctionAbilityConstraint>
            allConstraints.addAll(constraints)
        }

        return FunctionTypeAnnotation(paramType, returnType, effects, allConstraints)
    }

    override fun visitSimpleType(ctx: FunkParser.SimpleTypeContext): Any {
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
            ctx.LPAREN() != null && ctx.typeAnnotation().isNotEmpty() -> {
                visit(ctx.typeAnnotation(0)) as TypeAnnotation
            }
            ctx.arrayListType() != null -> visit(ctx.arrayListType())
            else -> throw IllegalArgumentException("Unknown simple type: ${ctx.text}")
        }
    }
    
    override fun visitEffectRow(ctx: FunkParser.EffectRowContext): Any {
        val effects = ctx.Identifier().map { it.text }
        return EffectRowAnnotation(effects)
    }
    
    override fun visitArrayListType(ctx: FunkParser.ArrayListTypeContext): Any {
        val elementType = visit(ctx.typeAnnotation()) as TypeAnnotation
        return ArrayTypeAnnotation(elementType)
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
     * Recursively substitute placeholder expressions with the given value
     */
    private fun substitutePlaceholdersInAST(expr: Expression, replacement: Expression): Expression {
        return when (expr) {
            is PlaceholderExpression -> replacement
            is FunctionCall -> FunctionCall(
                substitutePlaceholdersInAST(expr.function, replacement),
                substitutePlaceholdersInAST(expr.arguments, replacement),
                expr.inferredType
            )
            is RecordLiteral -> RecordLiteral(
                expr.entries.map { entry ->
                    when (entry) {
                        is RecordFieldEntry -> RecordFieldEntry(
                            entry.name, 
                            entry.value?.let { substitutePlaceholdersInAST(it, replacement) }
                        )
                        is RecordSpreadEntry -> RecordSpreadEntry(
                            substitutePlaceholdersInAST(entry.expression, replacement)
                        )
                    }
                },
                expr.restField,
                expr.inferredType
            )
            is BinaryOperation -> BinaryOperation(
                substitutePlaceholdersInAST(expr.left, replacement),
                expr.operator,
                substitutePlaceholdersInAST(expr.right, replacement),
                expr.inferredType
            )
            is UnaryOperation -> UnaryOperation(
                expr.operator,
                substitutePlaceholdersInAST(expr.operand, replacement),
                expr.inferredType
            )
            is MemberAccess -> MemberAccess(
                substitutePlaceholdersInAST(expr.receiver, replacement),
                expr.member,
                expr.inferredType
            )
            is IfExpression -> IfExpression(
                substitutePlaceholdersInAST(expr.condition, replacement),
                substitutePlaceholdersInAST(expr.thenBranch, replacement),
                substitutePlaceholdersInAST(expr.elseBranch, replacement),
                expr.inferredType
            )
            // For all other expressions (literals, identifiers, etc.), return as-is
            else -> expr
        }
    }
}