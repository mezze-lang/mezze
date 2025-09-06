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
        val frameDescriptor = FrameDescriptor.newBuilder().build()
        
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
                    // Convert let binding to a statement that defines the variable
                    val expressionNode = convertExpression(item.expression.expression)
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
     * Convert an expression AST node to a Truffle expression node
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
     * Convert literal values
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
        
        return FunkHandleNode(expressionNode, handlers)
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
}
