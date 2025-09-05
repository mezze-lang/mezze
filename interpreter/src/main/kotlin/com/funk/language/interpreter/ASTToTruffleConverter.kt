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
        
        // For now, we'll create a simple program that evaluates to null
        // TODO: Implement proper program execution with statements and final expression
        val bodyNode = FunkNullLiteralNode()
        
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
        // TODO: Implement lambda conversion
        // This requires creating a new root node for the lambda body
        throw FunkException.runtimeError("Lambda expressions not yet implemented")
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
        // TODO: Implement match expression conversion with pattern matching
        throw FunkException.runtimeError("Match expressions not yet implemented")
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
        // TODO: Implement do expression conversion
        throw FunkException.runtimeError("Do expressions not yet implemented")
    }
    
    /**
     * Convert block expressions
     */
    private fun convertBlockExpression(block: BlockExpression): FunkExpressionNode {
        // TODO: Implement block expression conversion
        throw FunkException.runtimeError("Block expressions not yet implemented")
    }
    
    /**
     * Convert handle expressions
     */
    private fun convertHandleExpression(handle: HandleExpression): FunkExpressionNode {
        // TODO: Implement handle expression conversion for effect system
        throw FunkException.runtimeError("Handle expressions not yet implemented")
    }
}
