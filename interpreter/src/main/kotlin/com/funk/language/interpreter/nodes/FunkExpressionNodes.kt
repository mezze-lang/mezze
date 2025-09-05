package com.funk.language.interpreter.nodes

import com.oracle.truffle.api.frame.VirtualFrame
import com.funk.language.interpreter.*

/**
 * Node for reading variables from the current scope
 */
class FunkReadVariableNode(private val identifier: String) : FunkExpressionNode() {
    override fun executeGeneric(frame: VirtualFrame): FunkValue {
        // TODO: Implement proper variable lookup with frame slots
        val context = getContext()
        return context.lookupGlobal(identifier) 
            ?: throw FunkException.unboundVariable(identifier, this)
    }
}

/**
 * Node for function calls
 */
class FunkFunctionCallNode(
    @Child private var functionNode: FunkExpressionNode,
    @Child private var argumentsNode: FunkExpressionNode
) : FunkExpressionNode() {
    
    override fun executeGeneric(frame: VirtualFrame): FunkValue {
        val function = functionNode.executeGeneric(frame)
        val arguments = argumentsNode.executeGeneric(frame)
        
        // TODO: Implement proper function calling
        throw FunkException.runtimeError("Function calls not yet implemented", this)
    }
}

/**
 * Node for record literals
 */
class FunkRecordLiteralNode(
    private val fieldNodes: Map<String, FunkExpressionNode>
) : FunkExpressionNode() {
    
    @Children private val childNodes = fieldNodes.values.toTypedArray()
    
    override fun executeGeneric(frame: VirtualFrame): FunkValue {
        val fields = fieldNodes.mapValues { (_, node) -> node.executeGeneric(frame) }
        return FunkRecord(fields)
    }
}

/**
 * Node for member access (record.field)
 */
class FunkMemberAccessNode(
    @Child private var recordNode: FunkExpressionNode,
    private val fieldName: String
) : FunkExpressionNode() {
    
    override fun executeGeneric(frame: VirtualFrame): FunkValue {
        val record = recordNode.executeTyped<FunkRecord>(frame)
        return record.readMember(fieldName)
    }
}

/**
 * Node for record updates
 */
class FunkRecordUpdateNode(
    @Child private var recordNode: FunkExpressionNode,
    private val updateNodes: Map<String, FunkExpressionNode>
) : FunkExpressionNode() {
    
    @Children private val childNodes = updateNodes.values.toTypedArray()
    
    override fun executeGeneric(frame: VirtualFrame): FunkValue {
        val record = recordNode.executeTyped<FunkRecord>(frame)
        val updates = updateNodes.mapValues { (_, node) -> node.executeGeneric(frame) }
        return record.withUpdates(updates)
    }
}

/**
 * Node for binary operations (+, -, *, etc.)
 */
class FunkBinaryOperationNode(
    @Child private var leftNode: FunkExpressionNode,
    private val operator: String,
    @Child private var rightNode: FunkExpressionNode
) : FunkExpressionNode() {
    
    override fun executeGeneric(frame: VirtualFrame): FunkValue {
        val left = leftNode.executeGeneric(frame)
        val right = rightNode.executeGeneric(frame)
        
        return when (operator) {
            "+" -> when {
                left is FunkInt && right is FunkInt -> FunkInt(left.value + right.value)
                left is FunkFloat && right is FunkFloat -> FunkFloat(left.value + right.value)
                left is FunkString && right is FunkString -> FunkString(left.value + right.value)
                else -> throw FunkException.typeError("Cannot add ${left::class.simpleName} and ${right::class.simpleName}", this)
            }
            "-" -> when {
                left is FunkInt && right is FunkInt -> FunkInt(left.value - right.value)
                left is FunkFloat && right is FunkFloat -> FunkFloat(left.value - right.value)
                else -> throw FunkException.typeError("Cannot subtract ${right::class.simpleName} from ${left::class.simpleName}", this)
            }
            "*" -> when {
                left is FunkInt && right is FunkInt -> FunkInt(left.value * right.value)
                left is FunkFloat && right is FunkFloat -> FunkFloat(left.value * right.value)
                else -> throw FunkException.typeError("Cannot multiply ${left::class.simpleName} and ${right::class.simpleName}", this)
            }
            "/" -> when {
                left is FunkInt && right is FunkInt -> {
                    if (right.value == 0L) throw FunkException.runtimeError("Division by zero", this)
                    FunkInt(left.value / right.value)
                }
                left is FunkFloat && right is FunkFloat -> FunkFloat(left.value / right.value)
                else -> throw FunkException.typeError("Cannot divide ${left::class.simpleName} by ${right::class.simpleName}", this)
            }
            "++" -> when {
                left is FunkString && right is FunkString -> FunkString(left.value + right.value)
                else -> throw FunkException.typeError("Cannot concatenate ${left::class.simpleName} and ${right::class.simpleName}", this)
            }
            "==" -> FunkBoolean(valuesEqual(left, right))
            "!=" -> FunkBoolean(!valuesEqual(left, right))
            "<" -> when {
                left is FunkInt && right is FunkInt -> FunkBoolean(left.value < right.value)
                left is FunkFloat && right is FunkFloat -> FunkBoolean(left.value < right.value)
                else -> throw FunkException.typeError("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}", this)
            }
            ">" -> when {
                left is FunkInt && right is FunkInt -> FunkBoolean(left.value > right.value)
                left is FunkFloat && right is FunkFloat -> FunkBoolean(left.value > right.value)
                else -> throw FunkException.typeError("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}", this)
            }
            "<=" -> when {
                left is FunkInt && right is FunkInt -> FunkBoolean(left.value <= right.value)
                left is FunkFloat && right is FunkFloat -> FunkBoolean(left.value <= right.value)
                else -> throw FunkException.typeError("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}", this)
            }
            ">=" -> when {
                left is FunkInt && right is FunkInt -> FunkBoolean(left.value >= right.value)
                left is FunkFloat && right is FunkFloat -> FunkBoolean(left.value >= right.value)
                else -> throw FunkException.typeError("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}", this)
            }
            "&&" -> {
                val leftBool = left as? FunkBoolean ?: throw FunkException.typeError("Expected boolean for &&", this)
                if (!leftBool.value) FunkBoolean(false) else {
                    val rightBool = right as? FunkBoolean ?: throw FunkException.typeError("Expected boolean for &&", this)
                    FunkBoolean(rightBool.value)
                }
            }
            "||" -> {
                val leftBool = left as? FunkBoolean ?: throw FunkException.typeError("Expected boolean for ||", this)
                if (leftBool.value) FunkBoolean(true) else {
                    val rightBool = right as? FunkBoolean ?: throw FunkException.typeError("Expected boolean for ||", this)
                    FunkBoolean(rightBool.value)
                }
            }
            else -> throw FunkException.runtimeError("Unsupported binary operator: $operator", this)
        }
    }
    
    private fun valuesEqual(left: FunkValue, right: FunkValue): Boolean {
        return when {
            left is FunkNull && right is FunkNull -> true
            left is FunkInt && right is FunkInt -> left.value == right.value
            left is FunkFloat && right is FunkFloat -> left.value == right.value
            left is FunkString && right is FunkString -> left.value == right.value
            left is FunkBoolean && right is FunkBoolean -> left.value == right.value
            else -> false
        }
    }
}

/**
 * Node for variant construction
 */
class FunkVariantConstructionNode(
    private val constructor: String,
    @Child private var valueNode: FunkExpressionNode?
) : FunkExpressionNode() {
    
    override fun executeGeneric(frame: VirtualFrame): FunkValue {
        val value = valueNode?.executeGeneric(frame)
        return FunkVariant(constructor, value)
    }
}

/**
 * Node for if expressions
 */
class FunkIfNode(
    @Child private var conditionNode: FunkExpressionNode,
    @Child private var thenNode: FunkExpressionNode,
    @Child private var elseNode: FunkExpressionNode
) : FunkExpressionNode() {
    
    override fun executeGeneric(frame: VirtualFrame): FunkValue {
        val condition = conditionNode.executeTyped<FunkBoolean>(frame)
        return if (condition.value) {
            thenNode.executeGeneric(frame)
        } else {
            elseNode.executeGeneric(frame)
        }
    }
}

/**
 * Node for pipe expressions (placeholder for now)
 */
class FunkPipeNode(
    @Child private var leftNode: FunkExpressionNode,
    @Child private var rightNode: FunkExpressionNode
) : FunkExpressionNode() {
    
    override fun executeGeneric(frame: VirtualFrame): FunkValue {
        // TODO: Implement proper pipe operator execution
        throw FunkException.runtimeError("Pipe operator not yet implemented", this)
    }
}
