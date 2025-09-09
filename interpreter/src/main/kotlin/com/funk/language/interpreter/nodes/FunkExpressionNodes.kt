package com.funk.language.interpreter.nodes

import com.oracle.truffle.api.frame.VirtualFrame
import com.funk.language.interpreter.*

/**
 * Node for reading variables from the current scope
 */
class FunkReadVariableNode(private val identifier: String) : FunkExpressionNode() {
    override fun executeGeneric(frame: VirtualFrame): FunkValue {
        val context = getContext()
        
        // Check if this is a qualified identifier (Module.item)
        if (identifier.contains(".")) {
            val parts = identifier.split(".")
            if (parts.size == 2) {
                val (moduleName, itemName) = parts
                return context.moduleRuntime.resolveAccess(moduleName, itemName, context)
            }
        }
        
        // Regular variable lookup - try context first, then native store as fallback
        var result = context.lookupGlobal(identifier)
        
        // WORKAROUND: If not found in context, try native-compatible global store
        // This handles the case where Native Image breaks Truffle context sharing
        if (result == null) {
            result = NativeGlobalStore.lookupGlobal(identifier)
        }
        
        return result ?: run {
            // If it's an uppercase identifier, treat it as a variant constructor
            if (identifier.first().isUpperCase()) {
                FunkVariant(identifier, null) // Simple constructor with no data
            } else {
                throw FunkException.unboundVariable(identifier)
            }
        }
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
        val context = getContext()
        
        // Check if this is an effect invocation
        // This is a simplified check - in a full implementation, we'd use type information
        if (functionNode is FunkReadVariableNode) {
            // We need to access the identifier, but it's private
            // For now, let's try to execute and catch the unbound variable exception
            try {
                val function = functionNode.executeGeneric(frame)
                val arguments = argumentsNode.executeGeneric(frame)
                
                return when (function) {
                    is FunkFunction -> {
                        function.call(arguments, context)
                    }
                    else -> {
                        throw FunkException.typeError(
                            "Cannot call non-function value: ${function::class.simpleName}"
                        )
                    }
                }
            } catch (e: FunkException) {
                if (e.message?.contains("Unbound variable") == true) {
                    // Effect invocations not supported - skip
                }
                throw e
            }
        }
        
        // Regular function call
        val function = functionNode.executeGeneric(frame)
        val arguments = argumentsNode.executeGeneric(frame)
        
        return when (function) {
            is FunkFunction -> {
                function.call(arguments, context)
            }
            else -> {
                throw FunkException.typeError(
                    "Cannot call non-function value: ${function::class.simpleName}"
                )
            }
        }
    }
    
    private fun extractVariableName(message: String?): String? {
        // Extract variable name from "Unbound variable: variableName" message
        return message?.substringAfter("Unbound variable: ")?.trim()
    }
    
    private fun isKnownEffect(name: String, context: FunkContext): Boolean {
        // Check if it's one of our known effects
        return name in listOf("Print", "ReadFile", "WriteFile")
    }
}

/**
 * Node for record literals
 */
class FunkRecordLiteralNode(
    private val fieldNodes: Map<String, FunkExpressionNode>
) : FunkExpressionNode() {
    
    @Children private val childNodes: Array<FunkExpressionNode> = fieldNodes.values.toTypedArray()
    
    override fun executeGeneric(frame: VirtualFrame): FunkValue {
        val fields = mutableMapOf<String, FunkValue>()
        for ((key, node) in fieldNodes) {
            fields[key] = node.executeGeneric(frame)
        }
        return FunkRecord(fields)
    }
}

/**
 * Node for lambda expressions
 */
class FunkLambdaNode(
    private val parameters: com.funk.language.parser.LambdaParameters,
    private val body: com.funk.language.parser.Expression
) : FunkExpressionNode() {
    
    override fun executeGeneric(frame: VirtualFrame): FunkValue {
        // Capture the current environment (for now, empty - we'll improve this)
        val capturedEnvironment = emptyMap<String, FunkValue>()
        
        return FunkFunction(parameters, body, capturedEnvironment)
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
        return record.readField(fieldName)
    }
}

/**
 * Node for record updates
 */
class FunkRecordUpdateNode(
    @Child private var recordNode: FunkExpressionNode,
    private val updateNodes: Map<String, FunkExpressionNode>
) : FunkExpressionNode() {
    
    @Children private val childNodes: Array<FunkExpressionNode> = updateNodes.values.toTypedArray()
    
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
                else -> throw FunkException.typeError("Cannot add ${left::class.simpleName} and ${right::class.simpleName}")
            }
            "-" -> when {
                left is FunkInt && right is FunkInt -> FunkInt(left.value - right.value)
                left is FunkFloat && right is FunkFloat -> FunkFloat(left.value - right.value)
                else -> throw FunkException.typeError("Cannot subtract ${right::class.simpleName} from ${left::class.simpleName}")
            }
            "*" -> when {
                left is FunkInt && right is FunkInt -> FunkInt(left.value * right.value)
                left is FunkFloat && right is FunkFloat -> FunkFloat(left.value * right.value)
                else -> throw FunkException.typeError("Cannot multiply ${left::class.simpleName} and ${right::class.simpleName}")
            }
            "/" -> when {
                left is FunkInt && right is FunkInt -> {
                    if (right.value == 0L) throw FunkException.runtimeError("Division by zero")
                    FunkInt(left.value / right.value)
                }
                left is FunkFloat && right is FunkFloat -> FunkFloat(left.value / right.value)
                else -> throw FunkException.typeError("Cannot divide ${left::class.simpleName} by ${right::class.simpleName}")
            }
            "++" -> when {
                left is FunkString && right is FunkString -> FunkString(left.value + right.value)
                else -> throw FunkException.typeError("Cannot concatenate ${left::class.simpleName} and ${right::class.simpleName}")
            }
            "==" -> FunkBoolean(valuesEqual(left, right))
            "!=" -> FunkBoolean(!valuesEqual(left, right))
            "<" -> when {
                left is FunkInt && right is FunkInt -> FunkBoolean(left.value < right.value)
                left is FunkFloat && right is FunkFloat -> FunkBoolean(left.value < right.value)
                else -> throw FunkException.typeError("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}")
            }
            ">" -> when {
                left is FunkInt && right is FunkInt -> FunkBoolean(left.value > right.value)
                left is FunkFloat && right is FunkFloat -> FunkBoolean(left.value > right.value)
                else -> throw FunkException.typeError("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}")
            }
            "<=" -> when {
                left is FunkInt && right is FunkInt -> FunkBoolean(left.value <= right.value)
                left is FunkFloat && right is FunkFloat -> FunkBoolean(left.value <= right.value)
                else -> throw FunkException.typeError("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}")
            }
            ">=" -> when {
                left is FunkInt && right is FunkInt -> FunkBoolean(left.value >= right.value)
                left is FunkFloat && right is FunkFloat -> FunkBoolean(left.value >= right.value)
                else -> throw FunkException.typeError("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}")
            }
            "&&" -> {
                val leftBool = left as? FunkBoolean ?: throw FunkException.typeError("Expected boolean for &&")
                if (!leftBool.value) FunkBoolean(false) else {
                    val rightBool = right as? FunkBoolean ?: throw FunkException.typeError("Expected boolean for &&")
                    FunkBoolean(rightBool.value)
                }
            }
            "||" -> {
                val leftBool = left as? FunkBoolean ?: throw FunkException.typeError("Expected boolean for ||")
                if (leftBool.value) FunkBoolean(true) else {
                    val rightBool = right as? FunkBoolean ?: throw FunkException.typeError("Expected boolean for ||")
                    FunkBoolean(rightBool.value)
                }
            }
            else -> throw FunkException.runtimeError("Unsupported binary operator: $operator")
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
 * Node for pipe expressions
 */
class FunkPipeNode(
    @Child private var leftNode: FunkExpressionNode,
    @Child private var rightNode: FunkExpressionNode
) : FunkExpressionNode() {
    
    override fun executeGeneric(frame: VirtualFrame): FunkValue {
        val leftValue = leftNode.executeGeneric(frame)
        
        // The right side should be a function that we call with the left value
        val rightValue = rightNode.executeGeneric(frame)
        
        if (rightValue is FunkFunction) {
            val context = getContext()
            
            // For pipe operations, we need to wrap the left value in a record
            // with a parameter name that matches the function's parameter
            val wrappedArg = when (rightValue.parameters) {
                is com.funk.language.parser.UntypedParameters -> {
                    if (rightValue.parameters.names.size == 1) {
                        // Single parameter - create record with that parameter name
                        val paramName = rightValue.parameters.names[0]
                        FunkRecord(mapOf(paramName to leftValue))
                    } else {
                        // Multiple parameters - can't pipe to this function
                        throw FunkException.typeError("Cannot pipe to function with multiple parameters")
                    }
                }
                else -> {
                    // For typed parameters, we'd need more complex logic
                    throw FunkException.typeError("Pipe to typed parameters not yet supported")
                }
            }
            
            return rightValue.call(wrappedArg, context)
        } else {
            throw FunkException.typeError("Right side of pipe must be a function")
        }
    }
}

/**
 * Node for block expressions
 */
class FunkBlockNode(
    @Children private val statementNodes: Array<FunkStatementNode>,
    @Child private var resultNode: FunkExpressionNode
) : FunkExpressionNode() {
    
    override fun executeGeneric(frame: VirtualFrame): FunkValue {
        // Execute all statements in sequence
        for (statementNode in statementNodes) {
            statementNode.executeVoid(frame)
        }
        
        // Return the result of the final expression
        return resultNode.executeGeneric(frame)
    }
}

/**
 * Node for do expressions (similar to block but with effect handling)
 */
class FunkDoNode(
    @Children private val statementNodes: Array<FunkStatementNode>,
    @Child private var resultNode: FunkExpressionNode
) : FunkExpressionNode() {
    
    override fun executeGeneric(frame: VirtualFrame): FunkValue {
        // Execute all statements in sequence (with potential effect handling)
        for (statementNode in statementNodes) {
            statementNode.executeVoid(frame)
        }
        
        // Return the result of the final expression
        return resultNode.executeGeneric(frame)
    }
}

/**
 * Node for match expressions
 */
class FunkMatchNode(
    @Child private var valueNode: FunkExpressionNode,
    @Children private val caseNodes: Array<FunkMatchCaseNode>
) : FunkExpressionNode() {
    
    override fun executeGeneric(frame: VirtualFrame): FunkValue {
        val value = valueNode.executeGeneric(frame)
        
        for (caseNode in caseNodes) {
            val matchResult = caseNode.tryMatch(value, frame)
            if (matchResult != null) {
                return matchResult
            }
        }
        
        throw FunkException.runtimeError("No pattern matched the value: ${value.getDisplayString()}")
    }
}

/**
 * Node for individual match cases
 */
class FunkMatchCaseNode(
    @Child private var patternNode: FunkPatternNode,
    @Child private var expressionNode: FunkExpressionNode
) : FunkNode() {
    
    fun tryMatch(value: FunkValue, frame: VirtualFrame): FunkValue? {
        val context = getContext()
        val childContext = context.createChildContext()
        
        return if (patternNode.matches(value, childContext)) {
            // Switch to child context for expression evaluation
            val oldContext = context.locals
            try {
                context.locals.putAll(childContext.locals)
                expressionNode.executeGeneric(frame)
            } finally {
                context.locals.clear()
                context.locals.putAll(oldContext)
            }
        } else {
            null
        }
    }
}

/**
 * Base class for pattern nodes
 */
abstract class FunkPatternNode : FunkNode() {
    abstract fun matches(value: FunkValue, context: FunkContext): Boolean
}

/**
 * Wildcard pattern node (_)
 */
class FunkWildcardPatternNode : FunkPatternNode() {
    override fun matches(value: FunkValue, context: FunkContext): Boolean = true
}

/**
 * Variable pattern node (binds value to variable)
 */
class FunkVariablePatternNode(private val variableName: String) : FunkPatternNode() {
    override fun matches(value: FunkValue, context: FunkContext): Boolean {
        context.defineLocal(variableName, value)
        return true
    }
}

/**
 * Constructor pattern node (variant matching)
 */
class FunkConstructorPatternNode(
    private val constructor: String,
    @Child private var recordPatternNode: FunkRecordPatternNode?
) : FunkPatternNode() {
    
    override fun matches(value: FunkValue, context: FunkContext): Boolean {
        if (value is FunkVariant && value.constructor == constructor) {
            return recordPatternNode?.let { recordPattern ->
                value.value?.let { variantValue ->
                    if (variantValue is FunkRecord) {
                        recordPattern.matches(variantValue, context)
                    } else false
                } ?: false
            } ?: (value.value == null)
        }
        return false
    }
}

/**
 * Record pattern node
 */
class FunkRecordPatternNode(
    @Children private val entryNodes: Array<FunkRecordPatternEntryNode>
) : FunkPatternNode() {
    
    override fun matches(value: FunkValue, context: FunkContext): Boolean {
        if (value is FunkRecord) {
            return entryNodes.all { entryNode ->
                entryNode.matches(value, context)
            }
        }
        return false
    }
}

/**
 * Record pattern entry node
 */
class FunkRecordPatternEntryNode(
    private val fieldName: String,
    @Child private var fieldPatternNode: FunkPatternNode?
) : FunkNode() {
    
    fun matches(record: FunkRecord, context: FunkContext): Boolean {
        val fieldValue = record.fields[fieldName] ?: return false
        
        return fieldPatternNode?.matches(fieldValue, context) ?: run {
            // No pattern means bind the field value to the field name
            context.defineLocal(fieldName, fieldValue)
            true
        }
    }
}
