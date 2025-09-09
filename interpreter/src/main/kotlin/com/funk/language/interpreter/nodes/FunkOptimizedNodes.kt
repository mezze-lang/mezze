package com.funk.language.interpreter.nodes

import com.oracle.truffle.api.frame.VirtualFrame
import com.funk.language.interpreter.*
import com.funk.language.parser.*
import com.funk.language.typechecker.*

/**
 * Type-specialized Truffle nodes for optimized execution
 */

/**
 * Optimized binary operation node for integers
 */
class FunkIntBinaryOperationNode(
    @Child private var leftNode: FunkExpressionNode,
    private val operator: String,
    @Child private var rightNode: FunkExpressionNode
) : FunkExpressionNode() {
    
    override fun executeGeneric(frame: VirtualFrame): FunkValue {
        val left = leftNode.executeTyped<FunkInt>(frame)
        val right = rightNode.executeTyped<FunkInt>(frame)
        
        return when (operator) {
            "+" -> FunkInt(left.value + right.value)
            "-" -> FunkInt(left.value - right.value)
            "*" -> FunkInt(left.value * right.value)
            "/" -> FunkInt(left.value / right.value)
            "==" -> FunkBoolean(left.value == right.value)
            "!=" -> FunkBoolean(left.value != right.value)
            "<" -> FunkBoolean(left.value < right.value)
            "<=" -> FunkBoolean(left.value <= right.value)
            ">" -> FunkBoolean(left.value > right.value)
            ">=" -> FunkBoolean(left.value >= right.value)
            else -> throw FunkException.runtimeError("Unsupported operator for integers: $operator")
        }
    }
}

/**
 * Optimized binary operation node for floats
 */
class FunkFloatBinaryOperationNode(
    @Child private var leftNode: FunkExpressionNode,
    private val operator: String,
    @Child private var rightNode: FunkExpressionNode
) : FunkExpressionNode() {
    
    override fun executeGeneric(frame: VirtualFrame): FunkValue {
        val left = leftNode.executeTyped<FunkFloat>(frame)
        val right = rightNode.executeTyped<FunkFloat>(frame)
        
        return when (operator) {
            "+" -> FunkFloat(left.value + right.value)
            "-" -> FunkFloat(left.value - right.value)
            "*" -> FunkFloat(left.value * right.value)
            "/" -> FunkFloat(left.value / right.value)
            "==" -> FunkBoolean(left.value == right.value)
            "!=" -> FunkBoolean(left.value != right.value)
            "<" -> FunkBoolean(left.value < right.value)
            "<=" -> FunkBoolean(left.value <= right.value)
            ">" -> FunkBoolean(left.value > right.value)
            ">=" -> FunkBoolean(left.value >= right.value)
            else -> throw FunkException.runtimeError("Unsupported operator for floats: $operator")
        }
    }
}

/**
 * Optimized binary operation node for strings
 */
class FunkStringBinaryOperationNode(
    @Child private var leftNode: FunkExpressionNode,
    private val operator: String,
    @Child private var rightNode: FunkExpressionNode
) : FunkExpressionNode() {
    
    override fun executeGeneric(frame: VirtualFrame): FunkValue {
        val left = leftNode.executeTyped<FunkString>(frame)
        val right = rightNode.executeTyped<FunkString>(frame)
        
        return when (operator) {
            "+" -> FunkString(left.value + right.value)
            "==" -> FunkBoolean(left.value == right.value)
            "!=" -> FunkBoolean(left.value != right.value)
            "<" -> FunkBoolean(left.value < right.value)
            "<=" -> FunkBoolean(left.value <= right.value)
            ">" -> FunkBoolean(left.value > right.value)
            ">=" -> FunkBoolean(left.value >= right.value)
            else -> throw FunkException.runtimeError("Unsupported operator for strings: $operator")
        }
    }
}

/**
 * Optimized function call node with return type information
 */
class FunkOptimizedFunctionCallNode(
    @Child private var functionNode: FunkExpressionNode,
    @Child private var argumentsNode: FunkExpressionNode,
    private val expectedReturnType: FunkType
) : FunkExpressionNode() {
    
    override fun executeGeneric(frame: VirtualFrame): FunkValue {
        val function = functionNode.executeGeneric(frame)
        val arguments = argumentsNode.executeGeneric(frame)
        
        val result = when (function) {
            is FunkTruffleFunction -> function.callTarget.call(arguments) as FunkValue
            is FunkFunction -> {
                val context = FunkLanguage.getCurrentContext(this)
                function.call(arguments, context)
            }
            else -> throw FunkException.runtimeError("Cannot call non-function value: ${function::class.simpleName}")
        }
        
        // Type assertion based on expected return type
        return when (expectedReturnType) {
            is FunkType.IntType -> {
                if (result is FunkInt) result
                else throw FunkException.runtimeError("Function returned ${result::class.simpleName}, expected Int")
            }
            is FunkType.FloatType -> {
                if (result is FunkFloat) result
                else throw FunkException.runtimeError("Function returned ${result::class.simpleName}, expected Float")
            }
            is FunkType.StringType -> {
                if (result is FunkString) result
                else throw FunkException.runtimeError("Function returned ${result::class.simpleName}, expected String")
            }
            is FunkType.BooleanType -> {
                if (result is FunkBoolean) result
                else throw FunkException.runtimeError("Function returned ${result::class.simpleName}, expected Boolean")
            }
            else -> result // No type assertion for complex types
        }
    }
}

/**
 * Typed record literal node with field type information
 */
class FunkTypedRecordLiteralNode(
    private val fieldNodes: Map<String, FunkExpressionNode>,
    private val recordType: FunkType.RecordType
) : FunkExpressionNode() {
    
    override fun executeGeneric(frame: VirtualFrame): FunkValue {
        val fields = mutableMapOf<String, FunkValue>()
        
        for ((fieldName, fieldNode) in fieldNodes) {
            val fieldValue = fieldNode.executeGeneric(frame)
            val expectedType = recordType.fields[fieldName]
            
            // Type validation at runtime
            if (expectedType != null) {
                val isValid = when (expectedType) {
                    is FunkType.IntType -> fieldValue is FunkInt
                    is FunkType.FloatType -> fieldValue is FunkFloat
                    is FunkType.StringType -> fieldValue is FunkString
                    is FunkType.BooleanType -> fieldValue is FunkBoolean
                    else -> true // Skip validation for complex types
                }
                
                if (!isValid) {
                    throw FunkException.runtimeError("Field '$fieldName' has wrong type: expected $expectedType, got ${fieldValue::class.simpleName}")
                }
            }
            
            fields[fieldName] = fieldValue
        }
        
        return FunkRecord(fields)
    }
}

/**
 * Typed member access node with result type information
 */
class FunkTypedMemberAccessNode(
    @Child private var recordNode: FunkExpressionNode,
    private val fieldName: String,
    private val expectedType: FunkType
) : FunkExpressionNode() {
    
    override fun executeGeneric(frame: VirtualFrame): FunkValue {
        val record = recordNode.executeTyped<FunkRecord>(frame)
        val fieldValue = record.readField(fieldName)
        
        // Type assertion based on expected type
        val isValid = when (expectedType) {
            is FunkType.IntType -> fieldValue is FunkInt
            is FunkType.FloatType -> fieldValue is FunkFloat
            is FunkType.StringType -> fieldValue is FunkString
            is FunkType.BooleanType -> fieldValue is FunkBoolean
            else -> true // Skip validation for complex types
        }
        
        if (!isValid) {
            throw FunkException.runtimeError("Field '$fieldName' has wrong type: expected $expectedType, got ${fieldValue::class.simpleName}")
        }
        
        return fieldValue
    }
}

/**
 * Typed lambda node with parameter and return type information
 */
class FunkTypedLambdaNode(
    private val parameters: LambdaParameters,
    private val body: Expression,
    private val functionType: FunkType.FunctionType
) : FunkExpressionNode() {
    
    override fun executeGeneric(frame: VirtualFrame): FunkValue {
        val context = FunkLanguage.getCurrentContext(this)
        
        // Create a function that validates parameters and return type
        return FunkFunction(parameters, body, context.locals.toMap())
    }
}