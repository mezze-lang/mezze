package com.funk.language.interpreter

import com.oracle.truffle.api.frame.FrameDescriptor
import com.funk.language.interpreter.nodes.*
import com.funk.language.parser.*
import com.funk.language.typechecker.*

/**
 * Converts parsed and type-checked AST nodes into executable Truffle nodes.
 * This is the bridge between our frontend (parser + type checker) and runtime.
 */
class ASTToTruffleConverter {
    
    /**
     * Convert a complete program into a Truffle root node
     */
    fun convertProgram(program: TypedProgram, language: FunkLanguage): FunkRootNode {
        val frameDescriptor = FrameDescriptor()
        
        // Convert all program items into statements and expressions
        if (program.items.isEmpty()) {
            return FunkRootNode(language, frameDescriptor, FunkNullLiteralNode(), "<main>")
        }
        
        // Separate let bindings from other items
        val statements = mutableListOf<FunkStatementNode>()
        var resultExpression: FunkExpressionNode = FunkNullLiteralNode()
        
        for (item in program.items) {
            when (item) {
                is TypedLetBinding -> {
                    // Convert let binding using type information for optimization
                    val expressionNode = convertTypedExpression(item.expression)
                    statements.add(FunkLetBindingNode(item.variableName, expressionNode))
                    
                    // The last let binding also serves as the result expression
                    resultExpression = FunkReadVariableNode(item.variableName)
                }
                else -> {
                    // Other items (type declarations, etc.) don't produce runtime values
                    // but we could handle them here if needed
                }
            }
        }
        
        // Create a block node that executes all statements and returns the final result
        val bodyNode = if (statements.isNotEmpty()) {
            FunkBlockNode(statements.toTypedArray(), resultExpression)
        } else {
            FunkNullLiteralNode()
        }
        
        return FunkRootNode(language, frameDescriptor, bodyNode, "<main>")
    }
    
    /**
     * Convert a typed expression to an optimized Truffle expression node
     */
    fun convertTypedExpression(typedExpr: TypedExpression): FunkExpressionNode {
        return convertExpressionWithType(typedExpr.expression, typedExpr.type)
    }
    
    /**
     * Convert an expression with type information for optimization
     */
    private fun convertExpressionWithType(expr: Expression, type: FunkType): FunkExpressionNode {
        return when (expr) {
            is Literal<*> -> convertTypedLiteral(expr, type)
            is Identifier -> FunkReadVariableNode(expr.name)
            is FunctionCall -> convertTypedFunctionCall(expr, type)
            is LambdaExpression -> convertTypedLambda(expr, type)
            is RecordLiteral -> convertTypedRecordLiteral(expr, type)
            is MemberAccess -> convertTypedMemberAccess(expr, type)
            is RecordUpdate -> convertTypedRecordUpdate(expr, type)
            is BinaryOperation -> convertTypedBinaryOperation(expr, type)
            is VariantConstruction -> convertTypedVariantConstruction(expr, type)
            is IfExpression -> convertTypedIfExpression(expr, type)
            is MatchExpression -> convertTypedMatchExpression(expr, type)
            is PipeExpression -> convertTypedPipeExpression(expr, type)
            is DoExpression -> convertTypedDoExpression(expr, type)
            is BlockExpression -> convertTypedBlockExpression(expr, type)
            is HandleExpression -> convertTypedHandleExpression(expr, type)
            is PlaceholderExpression -> {
                throw FunkException.runtimeError("Unexpected placeholder expression in runtime")
            }
            else -> throw FunkException.runtimeError("Unsupported expression type: ${expr::class.simpleName}")
        }
    }

    /**
     * Convert an expression AST node to a Truffle expression node (legacy, untyped)
     */
    fun convertExpression(expr: Expression): FunkExpressionNode {
        return when (expr) {
            is Literal<*> -> convertLiteral(expr)
            is Identifier -> FunkReadVariableNode(expr.name)
            is FunctionCall -> convertFunctionCall(expr)
            is LambdaExpression -> convertLambda(expr)
            is RecordLiteral -> convertRecordLiteral(expr)
            is MemberAccess -> convertMemberAccess(expr)
            is RecordUpdate -> convertRecordUpdate(expr)
            is BinaryOperation -> convertBinaryOperation(expr)
            is VariantConstruction -> convertVariantConstruction(expr)
            is IfExpression -> convertIfExpression(expr)
            is MatchExpression -> convertMatchExpression(expr)
            is PipeExpression -> convertPipeExpression(expr)
            is DoExpression -> convertDoExpression(expr)
            is BlockExpression -> convertBlockExpression(expr)
            is HandleExpression -> convertHandleExpression(expr)
            is PlaceholderExpression -> {
                // Placeholders should have been transformed away by the parser
                throw FunkException.runtimeError("Unexpected placeholder expression in runtime")
            }
            else -> throw FunkException.runtimeError("Unsupported expression type: ${expr::class.simpleName}")
        }
    }
    
    /**
     * Convert typed literal values with optimization
     */
    private fun convertTypedLiteral(literal: Literal<*>, type: FunkType): FunkExpressionNode {
        val value = literal.value
        return when (type) {
            is FunkType.IntType -> when (value) {
                is Int -> FunkIntLiteralNode(value.toLong())
                is Long -> FunkIntLiteralNode(value)
                else -> throw FunkException.runtimeError("Type mismatch: expected Int, got ${value?.let { it::class.simpleName }}")
            }
            is FunkType.FloatType -> when (value) {
                is Float -> FunkFloatLiteralNode(value.toDouble())
                is Double -> FunkFloatLiteralNode(value)
                is Int -> FunkFloatLiteralNode(value.toDouble()) // Auto-promotion
                is Long -> FunkFloatLiteralNode(value.toDouble()) // Auto-promotion
                else -> throw FunkException.runtimeError("Type mismatch: expected Float, got ${value?.let { it::class.simpleName }}")
            }
            is FunkType.StringType -> when (value) {
                is String -> FunkStringLiteralNode(value)
                else -> throw FunkException.runtimeError("Type mismatch: expected String, got ${value?.let { it::class.simpleName }}")
            }
            is FunkType.BooleanType -> when (value) {
                is Boolean -> FunkBooleanLiteralNode(value)
                else -> throw FunkException.runtimeError("Type mismatch: expected Boolean, got ${value?.let { it::class.simpleName }}")
            }
            else -> convertLiteral(literal) // Fallback to untyped conversion
        }
    }

    /**
     * Convert literal values (legacy, untyped)
     */
    private fun convertLiteral(literal: Literal<*>): FunkExpressionNode {
        return when (val value = literal.value) {
            is Int -> FunkIntLiteralNode(value.toLong())
            is Long -> FunkIntLiteralNode(value)
            is Float -> FunkFloatLiteralNode(value.toDouble())
            is Double -> FunkFloatLiteralNode(value)
            is String -> FunkStringLiteralNode(value)
            is Boolean -> FunkBooleanLiteralNode(value)
            null -> FunkNullLiteralNode()
            else -> throw FunkException.runtimeError("Unsupported literal type: ${value::class.simpleName}")
        }
    }
    
    /**
     * Convert function calls
     */
    private fun convertFunctionCall(call: FunctionCall): FunkExpressionNode {
        val functionNode = convertExpression(call.function)
        val argumentsNode = convertExpression(call.arguments)
        return FunkFunctionCallNode(functionNode, argumentsNode)
    }
    
    /**
     * Convert lambda expressions
     */
    private fun convertLambda(lambda: LambdaExpression): FunkExpressionNode {
        // Create a FunkFunction value that can be called later
        // The function will capture the current environment when created
        return FunkLambdaNode(lambda.parameters, lambda.body)
    }
    
    /**
     * Convert record literals
     */
    private fun convertRecordLiteral(record: RecordLiteral): FunkExpressionNode {
        val fieldNodes = record.entries.associate { entry ->
            entry.name to (entry.value?.let { convertExpression(it) } ?: FunkReadVariableNode(entry.name))
        }
        return FunkRecordLiteralNode(fieldNodes)
    }
    
    /**
     * Convert member access
     */
    private fun convertMemberAccess(access: MemberAccess): FunkExpressionNode {
        val recordNode = convertExpression(access.receiver)
        return FunkMemberAccessNode(recordNode, access.member)
    }
    
    /**
     * Convert record updates
     */
    private fun convertRecordUpdate(update: RecordUpdate): FunkExpressionNode {
        val recordNode = convertExpression(update.record)
        val updateNodes = update.updates.associate { entry ->
            entry.name to (entry.value?.let { convertExpression(it) } ?: FunkReadVariableNode(entry.name))
        }
        return FunkRecordUpdateNode(recordNode, updateNodes)
    }
    
    /**
     * Convert binary operations
     */
    private fun convertBinaryOperation(binOp: BinaryOperation): FunkExpressionNode {
        val leftNode = convertExpression(binOp.left)
        val rightNode = convertExpression(binOp.right)
        return FunkBinaryOperationNode(leftNode, binOp.operator, rightNode)
    }
    
    /**
     * Convert variant construction
     */
    private fun convertVariantConstruction(variant: VariantConstruction): FunkExpressionNode {
        val valueNode = variant.value?.let { convertExpression(it) }
        return FunkVariantConstructionNode(variant.constructor, valueNode)
    }
    
    /**
     * Convert if expressions
     */
    private fun convertIfExpression(ifExpr: IfExpression): FunkExpressionNode {
        val conditionNode = convertExpression(ifExpr.condition)
        val thenNode = convertExpression(ifExpr.thenBranch)
        val elseNode = convertExpression(ifExpr.elseBranch)
        return FunkIfNode(conditionNode, thenNode, elseNode)
    }
    
    /**
     * Convert match expressions
     */
    private fun convertMatchExpression(match: MatchExpression): FunkExpressionNode {
        val valueNode = convertExpression(match.expression)
        val caseNodes = match.cases.map { case ->
            val patternNode = convertPattern(case.pattern)
            val expressionNode = convertExpression(case.expression)
            FunkMatchCaseNode(patternNode, expressionNode)
        }
        return FunkMatchNode(valueNode, caseNodes.toTypedArray())
    }
    
    /**
     * Convert pattern nodes
     */
    private fun convertPattern(pattern: Pattern): FunkPatternNode {
        return when (pattern) {
            is WildcardPattern -> FunkWildcardPatternNode()
            is VariablePattern -> FunkVariablePatternNode(pattern.name)
            is ConstructorPattern -> {
                val recordPatternNode = pattern.recordPattern?.let { convertRecordPattern(it) }
                FunkConstructorPatternNode(pattern.constructor, recordPatternNode)
            }
            is RecordPattern -> convertRecordPattern(pattern)
        }
    }
    
    /**
     * Convert record pattern nodes
     */
    private fun convertRecordPattern(pattern: RecordPattern): FunkRecordPatternNode {
        val entryNodes = pattern.entries.map { entry ->
            val fieldPatternNode = entry.pattern?.let { convertPattern(it) }
            FunkRecordPatternEntryNode(entry.name, fieldPatternNode)
        }
        return FunkRecordPatternNode(entryNodes.toTypedArray())
    }
    
    /**
     * Convert pipe expressions
     */
    private fun convertPipeExpression(pipe: PipeExpression): FunkExpressionNode {
        val leftNode = convertExpression(pipe.left)
        val rightNode = convertExpression(pipe.right)
        return FunkPipeNode(leftNode, rightNode)
    }
    
    /**
     * Convert do expressions
     */
    private fun convertDoExpression(doExpr: DoExpression): FunkExpressionNode {
        val statementNodes = doExpr.statements.map { convertStatement(it) }.toTypedArray()
        val resultNode = convertExpression(doExpr.result)
        return FunkDoNode(statementNodes, resultNode)
    }
    
    /**
     * Convert block expressions
     */
    private fun convertBlockExpression(block: BlockExpression): FunkExpressionNode {
        val statementNodes = block.statements.map { convertStatement(it) }.toTypedArray()
        val resultNode = convertExpression(block.result)
        return FunkBlockNode(statementNodes, resultNode)
    }
    
    /**
     * Convert a statement AST node to a Truffle statement node
     */
    private fun convertStatement(stmt: Statement): FunkStatementNode {
        return when (stmt) {
            is LetBinding -> FunkLetBindingNode(stmt.variableName, convertExpression(stmt.expression))
            is ExpressionStatement -> FunkExpressionStatementNode(convertExpression(stmt.expression))
            is ResumeStatement -> FunkResumeStatementNode(convertExpression(stmt.expression))
            else -> throw FunkException.runtimeError("Unsupported statement type: ${stmt::class.simpleName}")
        }
    }
    
    /**
     * Convert handle expressions
     */
    private fun convertHandleExpression(handle: HandleExpression): FunkExpressionNode {
        val expressionNode = convertExpression(handle.expression)
        
        // Convert handle cases to handler map
        val handlers = handle.cases.associate { case ->
            case.effectName to convertBlockToExpression(case.statements)
        }
        
        // Handle expressions not supported - return expression directly
        return expressionNode
    }
    
    /**
     * Convert a list of statements to a single expression node
     */
    private fun convertBlockToExpression(statements: List<Statement>): FunkExpressionNode {
        return when {
            statements.isEmpty() -> FunkNullLiteralNode()
            statements.size == 1 -> {
                when (val stmt = statements[0]) {
                    is LetBinding -> convertExpression(stmt.expression)
                    else -> FunkNullLiteralNode()
                }
            }
            else -> {
                // For multiple statements, we'd need a block node
                // For now, just return the last expression
                val lastStmt = statements.last()
                when (lastStmt) {
                    is LetBinding -> convertExpression(lastStmt.expression)
                    else -> FunkNullLiteralNode()
                }
            }
        }
    }
    
    // ===== TYPE-AWARE CONVERSION METHODS =====
    
    /**
     * Convert typed binary operations with optimization
     */
    private fun convertTypedBinaryOperation(binOp: BinaryOperation, type: FunkType): FunkExpressionNode {
        // For arithmetic operations on known types, we can use specialized nodes
        return when (type) {
            is FunkType.IntType -> {
                val leftNode = convertExpression(binOp.left)
                val rightNode = convertExpression(binOp.right)
                FunkIntBinaryOperationNode(leftNode, binOp.operator, rightNode)
            }
            is FunkType.FloatType -> {
                val leftNode = convertExpression(binOp.left)
                val rightNode = convertExpression(binOp.right)
                FunkFloatBinaryOperationNode(leftNode, binOp.operator, rightNode)
            }
            is FunkType.StringType -> {
                val leftNode = convertExpression(binOp.left)
                val rightNode = convertExpression(binOp.right)
                FunkStringBinaryOperationNode(leftNode, binOp.operator, rightNode)
            }
            else -> convertBinaryOperation(binOp) // Fallback to generic
        }
    }
    
    /**
     * Convert typed function calls with optimization
     */
    private fun convertTypedFunctionCall(call: FunctionCall, type: FunkType): FunkExpressionNode {
        // We can optimize based on the return type and function type
        val functionNode = convertExpression(call.function)
        val argumentsNode = convertExpression(call.arguments)
        
        return when (type) {
            is FunkType.IntType -> FunkOptimizedFunctionCallNode(functionNode, argumentsNode, FunkType.IntType)
            is FunkType.FloatType -> FunkOptimizedFunctionCallNode(functionNode, argumentsNode, FunkType.FloatType)
            is FunkType.StringType -> FunkOptimizedFunctionCallNode(functionNode, argumentsNode, FunkType.StringType)
            is FunkType.BooleanType -> FunkOptimizedFunctionCallNode(functionNode, argumentsNode, FunkType.BooleanType)
            else -> FunkFunctionCallNode(functionNode, argumentsNode) // Generic fallback
        }
    }
    
    /**
     * Convert typed record literals with optimization
     */
    private fun convertTypedRecordLiteral(record: RecordLiteral, type: FunkType): FunkExpressionNode {
        return when (type) {
            is FunkType.RecordType -> {
                // We know the exact field types, so we can optimize field access
                val fieldNodes = record.entries.associate { entry ->
                    val fieldType = type.fields[entry.name]
                    val valueNode = entry.value?.let { value ->
                        if (fieldType != null) {
                            convertExpressionWithType(value, fieldType)
                        } else {
                            convertExpression(value)
                        }
                    } ?: FunkReadVariableNode(entry.name)
                    entry.name to valueNode
                }
                FunkTypedRecordLiteralNode(fieldNodes, type)
            }
            else -> convertRecordLiteral(record) // Fallback
        }
    }
    
    /**
     * Convert typed member access with optimization
     */
    private fun convertTypedMemberAccess(access: MemberAccess, type: FunkType): FunkExpressionNode {
        val recordNode = convertExpression(access.receiver)
        return FunkTypedMemberAccessNode(recordNode, access.member, type)
    }
    
    /**
     * Convert typed lambda expressions with optimization
     */
    private fun convertTypedLambda(lambda: LambdaExpression, type: FunkType): FunkExpressionNode {
        return when (type) {
            is FunkType.FunctionType -> {
                // We know the parameter and return types
                FunkTypedLambdaNode(lambda.parameters, lambda.body, type)
            }
            else -> convertLambda(lambda) // Fallback
        }
    }
    
    // Stub implementations for other typed methods (fallback to untyped for now)
    private fun convertTypedRecordUpdate(update: RecordUpdate, type: FunkType) = convertRecordUpdate(update)
    private fun convertTypedVariantConstruction(variant: VariantConstruction, type: FunkType) = convertVariantConstruction(variant)
    private fun convertTypedIfExpression(ifExpr: IfExpression, type: FunkType) = convertIfExpression(ifExpr)
    private fun convertTypedMatchExpression(match: MatchExpression, type: FunkType) = convertMatchExpression(match)
    private fun convertTypedPipeExpression(pipe: PipeExpression, type: FunkType) = convertPipeExpression(pipe)
    private fun convertTypedDoExpression(doExpr: DoExpression, type: FunkType) = convertDoExpression(doExpr)
    private fun convertTypedBlockExpression(block: BlockExpression, type: FunkType) = convertBlockExpression(block)
    private fun convertTypedHandleExpression(handle: HandleExpression, type: FunkType) = convertHandleExpression(handle)
}
