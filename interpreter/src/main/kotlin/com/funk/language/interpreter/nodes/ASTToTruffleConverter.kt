package com.funk.language.interpreter.nodes

import com.oracle.truffle.api.frame.FrameDescriptor
import com.funk.language.ast.*
import com.funk.language.interpreter.nodes.literal.*
import com.funk.language.interpreter.nodes.arithmetic.*
import com.funk.language.interpreter.nodes.comparison.*
import com.funk.language.interpreter.nodes.control.*
import com.funk.language.interpreter.nodes.function.*
import com.funk.language.interpreter.nodes.pattern.*
import com.funk.language.interpreter.nodes.variable.*
import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkInt
import com.funk.language.interpreter.FunkFloat
import com.funk.language.interpreter.FunkString
import com.funk.language.interpreter.FunkBool

/**
 * Converts Funk AST nodes to optimized Truffle nodes
 */
class ASTToTruffleConverter {
    
    private var frameDescriptorBuilder = FrameDescriptor.newBuilder()
    private val variableSlots = mutableMapOf<String, Int>()
    
    /**
     * Convert a program to a Truffle node
     */
    fun convertProgram(program: Program): FunkNode {
        val itemNodes = program.items.map { convertProgramItem(it) }.toTypedArray()
        return SequenceNode(itemNodes)
    }
    
    /**
     * Convert a program item to a Truffle node
     */
    private fun convertProgramItem(item: ProgramItem): FunkNode {
        return when (item) {
            is LetBinding -> convertLetBinding(item)
            is TopLevelExpression -> convertExpression(item.expression)
            is TypeDeclaration -> NullLiteralNode() // Type declarations don't produce runtime values
            is EffectDeclaration -> NullLiteralNode() // Effect declarations handled separately
            is ImplDeclaration -> NullLiteralNode() // Implementation declarations handled separately
            is AbilityDeclaration -> NullLiteralNode() // Ability declarations handled separately
            is ImplAbilityDeclaration -> NullLiteralNode() // Ability implementations handled separately
            is UseDeclaration -> NullLiteralNode() // Use declarations resolved at type-check time
            is PubUseDeclaration -> NullLiteralNode() // Pub use declarations resolved at type-check time
            else -> throw RuntimeException("Unsupported program item: ${item::class.simpleName}")
        }
    }
    
    /**
     * Convert a let binding to a Truffle node
     */
    private fun convertLetBinding(letBinding: LetBinding): FunkNode {
        val slot = getOrCreateSlot(letBinding.variableName)
        val valueNode = convertExpression(letBinding.expression)
        return SimpleWriteVariableNode(slot, valueNode)
    }
    
    /**
     * Convert an expression to a Truffle node
     */
    fun convertExpression(expr: Expression): FunkNode {
        println("DEBUG: Converting expression: ${expr::class.simpleName}")
        return when (expr) {
            is IntLiteral -> IntLiteralNode(expr.value)
            is FloatLiteral -> FloatLiteralNode(expr.value)
            is FractionLiteral -> FractionLiteralNode(expr.numerator, expr.denominator)
            is StringLiteral -> {
                val parts = expr.parts
                if (expr.isInterpolated && parts != null) {
                    // Convert to InterpolatedStringNode with part nodes
                    val partNodes = parts.map { part ->
                        when (part) {
                            is StringPart -> StringLiteralNode(part.value)
                            is ExpressionPart -> convertExpression(part.expression)
                        }
                    }.toTypedArray()
                    InterpolatedStringNode(partNodes)
                } else {
                    StringLiteralNode(expr.value)
                }
            }
            is BooleanLiteral -> BooleanLiteralNode(expr.value)
            is Identifier -> convertIdentifier(expr)
            is BinaryOperation -> convertBinaryOperation(expr)
            is UnaryOperation -> convertUnaryOperation(expr)
            is IfExpression -> convertIfExpression(expr)
            is MatchExpression -> convertMatchExpression(expr)
            is FunctionCall -> convertFunctionCall(expr)
            is LambdaExpression -> convertLambdaExpression(expr)
            is RecordLiteral -> convertRecordLiteral(expr)
            is ArrayLiteral -> convertArrayLiteral(expr)
            is ArrayAccess -> convertArrayAccess(expr)
            is MemberAccess -> convertMemberAccess(expr)
            is VariantConstruction -> convertVariantConstruction(expr)
            is DoExpression -> convertDoExpression(expr)
            is PerformExpression -> convertPerformExpression(expr)
            else -> throw RuntimeException("Unsupported expression: ${expr::class.simpleName}")
        }
    }
    
    /**
     * Convert an identifier to a variable read node or global lookup
     */
    private fun convertIdentifier(identifier: Identifier): FunkNode {
        // Check if this is a built-in function that should be resolved globally
        if (isBuiltinFunction(identifier.name)) {
            return GlobalLookupNode(identifier.name)
        }
        
        // Otherwise, treat as a local variable
        val slot = getOrCreateSlot(identifier.name)
        return SimpleReadVariableNode(slot)
    }
    
    /**
     * Check if an identifier is a built-in function
     */
    private fun isBuiltinFunction(name: String): Boolean {
        return when (name) {
            "intToString", "floatToString", "boolToString", "stringToInt",
            "print", "debug", "abs", "max", "min", "length", "concat",
            "arrayLength", "arrayGet", "arraySet", "arrayAdd", "arrayRemove",
            "arrayConcat", "arrayIsEmpty" -> true
            else -> false
        }
    }
    
    /**
     * Convert a binary operation to a specialized arithmetic/comparison node
     */
    private fun convertBinaryOperation(binOp: BinaryOperation): FunkNode {
        val leftNode = convertExpression(binOp.left)
        val rightNode = convertExpression(binOp.right)
        
        return when (binOp.operator) {
            "+" -> createAddNode(leftNode, rightNode)
            "-" -> createSubtractNode(leftNode, rightNode)
            "*" -> createMultiplyNode(leftNode, rightNode)
            "/" -> createDivideNode(leftNode, rightNode)
            "==" -> createEqualNode(leftNode, rightNode)
            "<" -> createLessThanNode(leftNode, rightNode)
            ">" -> createGreaterThanNode(leftNode, rightNode)
            "<=" -> createLessEqualNode(leftNode, rightNode)
            ">=" -> createGreaterEqualNode(leftNode, rightNode)
            else -> throw RuntimeException("Unsupported binary operator: ${binOp.operator}")
        }
    }
    
    /**
     * Convert a unary operation to a Truffle node
     */
    private fun convertUnaryOperation(unaryOp: UnaryOperation): FunkNode {
        val operandNode = convertExpression(unaryOp.operand)
        
        return when (unaryOp.operator) {
            "-" -> createNegateNode(operandNode)
            "!" -> createNotNode(operandNode)
            else -> throw RuntimeException("Unsupported unary operator: ${unaryOp.operator}")
        }
    }
    
    /**
     * Convert an if expression to a conditional node
     */
    private fun convertIfExpression(ifExpr: IfExpression): FunkNode {
        val conditionNode = convertExpression(ifExpr.condition)
        val thenNode = convertExpression(ifExpr.thenBranch)
        val elseNode = convertExpression(ifExpr.elseBranch)
        
        return IfNode(conditionNode, thenNode, elseNode)
    }
    
    /**
     * Convert a match expression to a pattern matching node
     */
    private fun convertMatchExpression(matchExpr: MatchExpression): FunkNode {
        val valueNode = convertExpression(matchExpr.expression)
        val caseNodes = matchExpr.cases.map { convertMatchCase(it) }.toTypedArray()
        
        return createPatternMatchNode(valueNode, caseNodes)
    }
    
    /**
     * Convert a match case to a pattern case node
     */
    private fun convertMatchCase(matchCase: MatchCase): PatternCaseNode {
        val patterns = matchCase.patterns.map { convertPattern(it) }.toTypedArray()
        val guardNode = matchCase.guard?.let { convertExpression(it) }
        val bodyNode = convertExpression(matchCase.expression)
        
        return createPatternCaseNode(patterns, guardNode, bodyNode)
    }
    
    /**
     * Convert a pattern to a pattern node
     */
    private fun convertPattern(pattern: Pattern): PatternNode {
        return when (pattern) {
            is VariablePattern -> VariablePatternNode(pattern.name)
            is WildcardPattern -> WildcardPatternNode()
            is LiteralPattern -> {
                // Convert literal patterns to LiteralPatternNode with the appropriate FunkValue
                val value = when (val lit = pattern.value) {
                    is IntLiteralValue -> FunkInt(lit.value)
                    is FloatLiteralValue -> FunkFloat(lit.value)
                    is StringLiteralValue -> FunkString(lit.value)
                    is BooleanLiteralValue -> FunkBool(lit.value)
                }
                LiteralPatternNode(value)
            }
            is ConstructorPattern -> {
                // For now, treat constructor patterns as literal patterns
                // This is a simplified implementation
                LiteralPatternNode(FunkString(pattern.constructor))
            }
            is RecordPattern -> {
                // For now, treat record patterns as wildcard patterns
                // This is a simplified implementation
                WildcardPatternNode()
            }
        }
    }
    
    /**
     * Convert a function call to a function call node
     */
    private fun convertFunctionCall(funcCall: FunctionCall): FunkNode {
        val functionNode = convertExpression(funcCall.function)
        val argumentsNode = convertExpression(funcCall.arguments)
        
        return createFunctionCallNode(functionNode, argumentsNode)
    }
    
    /**
     * Convert a lambda expression to a function definition node
     */
    private fun convertLambdaExpression(lambda: LambdaExpression): FunkNode {
        val paramNames = when (val params = lambda.parameters) {
            is UntypedParameters -> params.names
            is TypedParameters -> params.recordType.entries.map { it.name }
        }
        
        // Create a new converter for the function body to manage its own frame
        val functionConverter = ASTToTruffleConverter()
        
        // Pre-register parameter names in the function's frame
        paramNames.forEach { paramName ->
            functionConverter.getOrCreateSlot(paramName)
        }
        
        val bodyNode = functionConverter.convertExpression(lambda.body)
        val functionFrameDescriptor = functionConverter.getFrameDescriptor()
        
        return FunctionDefinitionNode("lambda", paramNames, bodyNode, functionFrameDescriptor)
    }
    
    /**
     * Convert a record literal to a record construction node
     */
    private fun convertRecordLiteral(recordLit: RecordLiteral): FunkNode {
        val fieldNodes = mutableMapOf<String, FunkNode>()
        
        // Handle spread entries first
        for (spreadEntry in recordLit.getSpreadEntries()) {
            // For now, we'll handle spread by evaluating the expression and merging at runtime
            // This is a simplified approach - a more sophisticated implementation would
            // analyze the spread expression at compile time when possible
            val spreadNode = convertExpression(spreadEntry.expression)
            // We'll need to create a special node type to handle spread at runtime
            // For now, we'll throw an error as spread syntax needs more work in Truffle
            throw RuntimeException("Spread syntax not yet fully supported in Truffle mode")
        }
        
        // Handle field entries
        for (entry in recordLit.getFieldEntries()) {
            fieldNodes[entry.name] = entry.value?.let { convertExpression(it) } ?: NullLiteralNode()
        }
        
        return RecordLiteralNode(fieldNodes)
    }
    
    /**
     * Convert an Array literal to an Array construction node
     */
    private fun convertArrayLiteral(arrayLit: ArrayLiteral): FunkNode {
        val elementNodes = arrayLit.elements.map { convertExpression(it) }.toTypedArray()
        return ArrayLiteralNode(elementNodes)
    }
    
    /**
     * Convert array access to an array access node
     */
    private fun convertArrayAccess(arrayAccess: ArrayAccess): FunkNode {
        val arrayNode = convertExpression(arrayAccess.array)
        val indexNode = convertExpression(arrayAccess.index)
        return ArrayAccessNode(arrayNode, indexNode)
    }
    
    /**
     * Convert member access (placeholder implementation)
     */
    private fun convertMemberAccess(memberAccess: MemberAccess): FunkNode {
        // For now, return a null literal - member access needs more complex handling
        return NullLiteralNode()
    }
    
    /**
     * Convert variant construction (placeholder implementation)
     */
    private fun convertVariantConstruction(variantConst: VariantConstruction): FunkNode {
        // For now, return a null literal - variant construction needs more complex handling
        return NullLiteralNode()
    }
    
    /**
     * Convert do expression to a parallel-aware node with runtime parallelism detection
     */
    private fun convertDoExpression(doExpr: DoExpression): FunkNode {
        // Use the factory to create the most appropriate do-expression node
        return com.funk.language.interpreter.nodes.control.DoExpressionNodeFactory
            .createDoExpressionNode(doExpr, this)
    }
    
    /**
     * Convert a statement to a Truffle node
     */
    private fun convertStatement(stmt: Statement): FunkNode {
        return when (stmt) {
            is LetStatement -> {
                val slot = getOrCreateSlot(stmt.variableName)
                val valueNode = convertExpression(stmt.expression)
                SimpleWriteVariableNode(slot, valueNode)
            }
            is ExpressionStatement -> convertExpression(stmt.expression)
            else -> throw RuntimeException("Unsupported statement: ${stmt::class.simpleName}")
        }
    }
    
    /**
     * Convert perform expression (placeholder implementation)
     */
    private fun convertPerformExpression(performExpr: PerformExpression): FunkNode {
        // For now, just convert the inner expression
        return convertExpression(performExpr.expression)
    }
    
    /**
     * Get or create a frame slot for a variable
     */
    fun getOrCreateSlot(variableName: String): Int {
        return variableSlots.getOrPut(variableName) {
            val slotIndex = variableSlots.size
            frameDescriptorBuilder.addSlot(com.oracle.truffle.api.frame.FrameSlotKind.Object, variableName, null)
            slotIndex
        }
    }
    
    /**
     * Get the frame descriptor for this converter
     */
    fun getFrameDescriptor(): FrameDescriptor = frameDescriptorBuilder.build()
    
    // Factory methods for creating specialized nodes
    
    private fun createAddNode(left: FunkNode, right: FunkNode): FunkNode {
        return AddNode(left, right)
    }
    
    private fun createSubtractNode(left: FunkNode, right: FunkNode): FunkNode {
        return SubtractNode(left, right)
    }
    
    private fun createMultiplyNode(left: FunkNode, right: FunkNode): FunkNode {
        return MultiplyNode(left, right)
    }
    
    private fun createDivideNode(left: FunkNode, right: FunkNode): FunkNode {
        return DivideNode(left, right)
    }
    
    private fun createEqualNode(left: FunkNode, right: FunkNode): FunkNode {
        return EqualNode(left, right)
    }
    
    private fun createLessThanNode(left: FunkNode, right: FunkNode): FunkNode {
        return LessThanNode(left, right)
    }
    
    private fun createGreaterThanNode(left: FunkNode, right: FunkNode): FunkNode {
        return GreaterThanNode(left, right)
    }
    
    private fun createLessEqualNode(left: FunkNode, right: FunkNode): FunkNode {
        return LessEqualNode(left, right)
    }
    
    private fun createGreaterEqualNode(left: FunkNode, right: FunkNode): FunkNode {
        return GreaterEqualNode(left, right)
    }
    
    private fun createNegateNode(operand: FunkNode): FunkNode {
        // Simple negation implementation
        return object : FunkNode() {
            @field:Child private var operandChild = operand
            
            override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame): FunkValue {
                val value = operandChild.execute(frame)
                return when (value) {
                    is FunkInt -> FunkInt(-value.value)
                    is FunkFloat -> com.funk.language.interpreter.FunkFloat(-value.value)
                    else -> throw RuntimeException("Cannot negate ${value::class.simpleName}")
                }
            }
        }
    }
    
    private fun createNotNode(operand: FunkNode): FunkNode {
        // Simple logical not implementation
        return object : FunkNode() {
            @field:Child private var operandChild = operand
            
            override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame): FunkValue {
                val value = operandChild.execute(frame)
                return when (value) {
                    is FunkBool -> FunkBool(!value.value)
                    else -> throw RuntimeException("Cannot apply logical not to ${value::class.simpleName}")
                }
            }
        }
    }
    
    private fun createPatternMatchNode(valueNode: FunkNode, caseNodes: Array<PatternCaseNode>): FunkNode {
        return PatternMatchNode(valueNode, caseNodes)
    }
    
    private fun createPatternCaseNode(patterns: Array<PatternNode>, guardNode: FunkNode?, bodyNode: FunkNode): PatternCaseNode {
        return PatternCaseNode(patterns, guardNode, bodyNode)
    }
    
    private fun createFunctionCallNode(functionNode: FunkNode, argumentsNode: FunkNode): FunkNode {
        return FunctionCallNode(functionNode, argumentsNode)
    }
}