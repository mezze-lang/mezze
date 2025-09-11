package com.funk.language.interpreter

import com.funk.language.parser.FunkParserImpl
import com.funk.language.ast.*
import com.funk.language.typechecker.TypeChecker
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode

/**
 * Truffle-integrated interpreter that works with the shared AST
 * This provides proper Truffle execution while using the unified AST architecture
 */
class FunkInterpreter {
    
    private val parser: FunkParserImpl?
    private val typeChecker: TypeChecker?
    
    // Constructor for full parsing/typechecking (used by funk exec)
    constructor() {
        parser = FunkParserImpl()
        typeChecker = TypeChecker()
    }
    
    // Constructor for pre-processed AST (used by compiled JARs)
    constructor(skipParserSetup: Boolean) {
        if (skipParserSetup) {
            parser = null
            typeChecker = null
        } else {
            parser = FunkParserImpl()
            typeChecker = TypeChecker()
        }
    }
    
    /**
     * Parse, typecheck, and execute a Funk program from source code
     */
    fun run(source: String): FunkValue {
        require(parser != null && typeChecker != null) { 
            "Parser and typechecker not available. Use constructor without skipParserSetup for source code execution." 
        }
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        return execute(typedProgram)
    }
    
    /**
     * Execute a pre-parsed and typechecked program directly
     */
    fun execute(program: Program): FunkValue {
        return executeProgram(program)
    }
    
    /**
     * Public method for testing - executes a program and returns both result and context
     */
    fun executeWithContext(program: Program): Pair<FunkValue, Map<String, FunkValue>> {
        val context = mutableMapOf<String, FunkValue>()
        var lastResult: FunkValue = FunkNull
        
        for (item in program.items) {
            lastResult = executeItem(item, context)
        }
        
        return Pair(lastResult, context.toMap())
    }
    
    /**
     * Execute a program (enhanced with type information)
     */
    private fun executeProgram(program: Program): FunkValue {
        // Simple execution context
        val context = mutableMapOf<String, FunkValue>()
        
        // Execute each item in the program
        var lastResult: FunkValue = FunkNull
        
        for (item in program.items) {
            lastResult = executeItem(item, context)
        }
        
        return lastResult
    }
    
    /**
     * Execute a single program item
     */
    private fun executeItem(item: ProgramItem, context: MutableMap<String, FunkValue>): FunkValue {
        return when (item) {
            is LetBinding -> {
                // Evaluate the expression and store it in context
                val value = evaluateExpression(item.expression, context)
                context[item.variableName] = value
                value
            }
            is TypeDeclaration -> {
                // Type declarations don't produce runtime values
                FunkNull
            }
            is TopLevelExpression -> {
                // Top-level expressions are evaluated and their result is returned
                evaluateExpression(item.expression, context)
            }
            else -> {
                throw RuntimeException("Unsupported program item: ${item::class.simpleName}")
            }
        }
    }
    
    /**
     * Evaluate an expression (simplified version)
     */
    private fun evaluateExpression(expr: Expression, context: MutableMap<String, FunkValue>): FunkValue {
        return when (expr) {
            is IntLiteral -> FunkInt(expr.value)
            is FloatLiteral -> FunkFloat(expr.value)
            is StringLiteral -> FunkString(expr.value)
            is BooleanLiteral -> FunkBoolean(expr.value)
            is Identifier -> {
                context[expr.name] ?: run {
                    // If it's an uppercase identifier, treat it as a variant constructor
                    if (expr.name.first().isUpperCase()) {
                        FunkVariant(expr.name, null) // Simple constructor with no data
                    } else {
                        throw RuntimeException("Undefined variable: ${expr.name}")
                    }
                }
            }
            is BinaryOperation -> {
                val left = evaluateExpression(expr.left, context)
                val right = evaluateExpression(expr.right, context)
                evaluateBinaryOperation(left, expr.operator, right)
            }
            is UnaryOperation -> {
                val operand = evaluateExpression(expr.operand, context)
                evaluateUnaryOperation(expr.operator, operand)
            }
            is RecordLiteral -> {
                val fields = expr.entries.associate { (name, fieldExpr) ->
                    name to (fieldExpr?.let { evaluateExpression(it, context) } ?: FunkNull)
                }
                FunkRecord(fields)
            }
            is MemberAccess -> {
                val record = evaluateExpression(expr.receiver, context)
                when (record) {
                    is FunkRecord -> record.fields[expr.member] 
                        ?: throw RuntimeException("Field '${expr.member}' not found in record")
                    else -> throw RuntimeException("Cannot access member '${expr.member}' on non-record value")
                }
            }
            is VariantConstruction -> {
                val data = expr.value?.let { evaluateExpression(it, context) }
                FunkVariant(expr.constructor, data)
            }
            is IfExpression -> {
                val condition = evaluateExpression(expr.condition, context)
                if (isTruthy(condition)) {
                    evaluateExpression(expr.thenBranch, context)
                } else {
                    evaluateExpression(expr.elseBranch, context)
                }
            }
            is FunctionCall -> {
                val function = evaluateExpression(expr.function, context)
                val argumentsValue = evaluateExpression(expr.arguments, context)
                
                // Handle record arguments for partial application
                if (argumentsValue is FunkRecord) {
                    callFunctionWithRecord(function, argumentsValue, context)
                } else {
                    // Otherwise treat as single argument (legacy)
                    val args = listOf(argumentsValue)
                    callFunction(function, args, context)
                }
            }
            is LambdaExpression -> {
                val paramNames = when (val params = expr.parameters) {
                    is UntypedParameters -> params.names
                    is TypedParameters -> {
                        // Extract parameter names from the typed parameters
                        params.recordType.entries.map { it.name }
                    }
                }
                SimpleFunkFunction(paramNames, expr.body, context.toMap()) // Capture current context
            }
            is DoExpression -> {
                var result: FunkValue = FunkNull
                for (stmt in expr.statements) {
                    result = executeStatement(stmt, context)
                }
                evaluateExpression(expr.result, context)
            }
            // PipeExpression and PlaceholderExpression removed - desugared during parsing
            is RecordUpdateExpression -> {
                // Evaluate record update: { baseRecord | field = value, ... }
                val baseRecord = evaluateExpression(expr.baseRecord, context)
                if (baseRecord !is FunkRecord) {
                    throw RuntimeException("Cannot update non-record value: $baseRecord")
                }
                
                // Create updated record with new field values
                val updatedFields = baseRecord.fields.toMutableMap()
                
                expr.updates.forEach { update ->
                    when (update.fieldPath.size) {
                        1 -> {
                            // Simple field update: x = v
                            val fieldName = update.fieldPath[0]
                            // For open records, we allow adding new fields
                            // The type checker has already validated this is allowed
                            val newValue = evaluateExpression(update.value, context)
                            updatedFields[fieldName] = newValue
                        }
                        2 -> {
                            // Nested field update: y.z = v
                            val outerField = update.fieldPath[0]
                            val innerField = update.fieldPath[1]
                            
                            if (!baseRecord.fields.containsKey(outerField)) {
                                throw RuntimeException("Field '$outerField' does not exist in record")
                            }
                            
                            val outerValue = baseRecord.fields[outerField]
                            if (outerValue !is FunkRecord) {
                                throw RuntimeException("Cannot access field '$innerField' on non-record value: $outerValue")
                            }
                            
                            if (!outerValue.fields.containsKey(innerField)) {
                                throw RuntimeException("Field '$innerField' does not exist in nested record")
                            }
                            
                            // Update the nested record
                            val updatedNestedFields = outerValue.fields.toMutableMap()
                            val newValue = evaluateExpression(update.value, context)
                            updatedNestedFields[innerField] = newValue
                            updatedFields[outerField] = FunkRecord(updatedNestedFields)
                        }
                        else -> {
                            throw RuntimeException("Nested field updates beyond one level are not supported")
                        }
                    }
                }
                
                FunkRecord(updatedFields)
            }
            is MatchExpression -> {
                val value = evaluateExpression(expr.expression, context)
                evaluateMatchExpression(value, expr.cases, context)
            }
            else -> {
                throw RuntimeException("Unsupported expression: ${expr::class.simpleName}")
            }
        }
    }
    
    // substitutePlaceholders method removed - pipe operator now desugared during parsing

    /**
     * Evaluate a match expression with pattern matching
     */
    private fun evaluateMatchExpression(value: FunkValue, cases: List<MatchCase>, context: MutableMap<String, FunkValue>): FunkValue {
        for (case in cases) {
            val patternContext = mutableMapOf<String, FunkValue>()
            if (matchesPattern(value, case.pattern, patternContext)) {
                // Create new context with pattern bindings
                val newContext = context.toMutableMap()
                newContext.putAll(patternContext)
                return evaluateExpression(case.expression, newContext)
            }
        }
        throw RuntimeException("No matching pattern found for value: $value")
    }
    
    /**
     * Check if a value matches a pattern and extract bindings
     */
    private fun matchesPattern(value: FunkValue, pattern: Pattern, bindings: MutableMap<String, FunkValue>): Boolean {
        return when (pattern) {
            is ConstructorPattern -> {
                if (value is FunkVariant) {
                    if (value.constructor == pattern.constructor) {
                        // If pattern has record data, match it
                        val recordPattern = pattern.recordPattern
                        if (recordPattern != null) {
                            val valueData = value.value
                            if (valueData != null) {
                                matchesPattern(valueData, recordPattern, bindings)
                            } else {
                                false
                            }
                        } else {
                            // No record pattern means no data expected
                            value.value == null
                        }
                    } else {
                        false
                    }
                } else {
                    false
                }
            }
            is RecordPattern -> {
                if (value is FunkRecord) {
                    // Match all fields in the pattern
                    pattern.entries.all { entry ->
                        val fieldValue = value.fields[entry.name]
                        if (fieldValue != null) {
                            val entryPattern = entry.pattern
                            if (entryPattern != null) {
                                matchesPattern(fieldValue, entryPattern, bindings)
                            } else {
                                // Shorthand pattern {x} binds the field value to variable x
                                bindings[entry.name] = fieldValue
                                true
                            }
                        } else {
                            false
                        }
                    }
                } else {
                    false
                }
            }
            is VariablePattern -> {
                // Bind the value to the variable
                bindings[pattern.name] = value
                true
            }
            is WildcardPattern -> {
                // Wildcard matches anything
                true
            }
            else -> {
                throw RuntimeException("Unsupported pattern: ${pattern::class.simpleName}")
            }
        }
    }

    /**
     * Execute a statement
     */
    private fun executeStatement(stmt: Statement, context: MutableMap<String, FunkValue>): FunkValue {
        return when (stmt) {
            is LetStatement -> {
                val value = evaluateExpression(stmt.expression, context)
                context[stmt.variableName] = value
                value
            }
            is ExpressionStatement -> {
                evaluateExpression(stmt.expression, context)
            }
            else -> {
                throw RuntimeException("Unsupported statement: ${stmt::class.simpleName}")
            }
        }
    }
    
    /**
     * Evaluate binary operations
     */
    private fun evaluateBinaryOperation(left: FunkValue, operator: String, right: FunkValue): FunkValue {
        return when (operator) {
            "+" -> when {
                left is FunkInt && right is FunkInt -> FunkInt(left.value + right.value)
                left is FunkFloat && right is FunkFloat -> FunkFloat(left.value + right.value)
                else -> throw RuntimeException("Cannot add ${left::class.simpleName} and ${right::class.simpleName}")
            }
            "++" -> when {
                left is FunkString && right is FunkString -> FunkString(left.value + right.value)
                else -> throw RuntimeException("Cannot concatenate ${left::class.simpleName} and ${right::class.simpleName}")
            }
            "-" -> when {
                left is FunkInt && right is FunkInt -> FunkInt(left.value - right.value)
                left is FunkFloat && right is FunkFloat -> FunkFloat(left.value - right.value)
                else -> throw RuntimeException("Cannot subtract ${right::class.simpleName} from ${left::class.simpleName}")
            }
            "*" -> when {
                left is FunkInt && right is FunkInt -> FunkInt(left.value * right.value)
                left is FunkFloat && right is FunkFloat -> FunkFloat(left.value * right.value)
                else -> throw RuntimeException("Cannot multiply ${left::class.simpleName} and ${right::class.simpleName}")
            }
            "/" -> when {
                left is FunkInt && right is FunkInt -> FunkInt(left.value / right.value)
                left is FunkFloat && right is FunkFloat -> FunkFloat(left.value / right.value)
                else -> throw RuntimeException("Cannot divide ${left::class.simpleName} by ${right::class.simpleName}")
            }
            "%" -> when {
                left is FunkInt && right is FunkInt -> FunkInt(left.value % right.value)
                else -> throw RuntimeException("Cannot take modulo of ${left::class.simpleName} and ${right::class.simpleName}")
            }
            "==" -> FunkBoolean(left == right)
            "!=" -> FunkBoolean(left != right)
            "<" -> when {
                left is FunkInt && right is FunkInt -> FunkBoolean(left.value < right.value)
                left is FunkFloat && right is FunkFloat -> FunkBoolean(left.value < right.value)
                else -> throw RuntimeException("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}")
            }
            ">" -> when {
                left is FunkInt && right is FunkInt -> FunkBoolean(left.value > right.value)
                left is FunkFloat && right is FunkFloat -> FunkBoolean(left.value > right.value)
                else -> throw RuntimeException("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}")
            }
            "&&" -> FunkBoolean(isTruthy(left) && isTruthy(right))
            "||" -> FunkBoolean(isTruthy(left) || isTruthy(right))
            else -> throw RuntimeException("Unknown binary operator: $operator")
        }
    }
    
    /**
     * Evaluate unary operations
     */
    private fun evaluateUnaryOperation(operator: String, operand: FunkValue): FunkValue {
        return when (operator) {
            "-" -> when (operand) {
                is FunkInt -> FunkInt(-operand.value)
                is FunkFloat -> FunkFloat(-operand.value)
                else -> throw RuntimeException("Cannot negate ${operand::class.simpleName}")
            }
            else -> throw RuntimeException("Unknown unary operator: $operator")
        }
    }
    
    /**
     * Call a function with arguments
     */
    /**
     * Call a function with record arguments, supporting partial application
     */
    private fun callFunctionWithRecord(function: FunkValue, arguments: FunkRecord, context: MutableMap<String, FunkValue>): FunkValue {
        return when (function) {
            is SimpleFunkFunction -> {
                val providedParams = arguments.fields.keys
                val requiredParams = function.parameters.toSet()
                val missingParams = requiredParams - providedParams
                
                if (missingParams.isEmpty()) {
                    // All parameters provided - execute function
                    val newContext = function.closure.toMutableMap()
                    // Bind all parameters
                    for (param in function.parameters) {
                        newContext[param] = arguments.fields[param] ?: FunkNull
                    }
                    evaluateExpression(function.body, newContext)
                } else {
                    // Partial application - create partial function
                    FunkPartialFunction(
                        function,
                        arguments.fields,
                        missingParams.toList()
                    )
                }
            }
            is FunkPartialFunction -> {
                // Calling a partial function - combine arguments
                val combinedArgs = function.providedArguments + arguments.fields
                val newRecord = FunkRecord(combinedArgs)
                callFunctionWithRecord(function.originalFunction, newRecord, context)
            }
            else -> throw RuntimeException("Cannot call non-function value: ${function::class.simpleName}")
        }
    }

    private fun callFunction(function: FunkValue, args: List<FunkValue>, context: MutableMap<String, FunkValue>): FunkValue {
        return when (function) {
            is SimpleFunkFunction -> {
                // Create new context with captured closure and parameters
                val newContext = function.closure.toMutableMap()
                
                // Bind parameters (simplified - assume list of parameter names)
                function.parameters.forEachIndexed { index, param ->
                    if (index < args.size) {
                        newContext[param] = args[index]
                    }
                }
                
                evaluateExpression(function.body, newContext)
            }
            else -> throw RuntimeException("Cannot call non-function value: ${function::class.simpleName}")
        }
    }
    
    /**
     * Check if a value is truthy
     */
    private fun isTruthy(value: FunkValue): Boolean {
        return when (value) {
            is FunkBoolean -> value.value
            is FunkNull -> false
            else -> true
        }
    }
}

/**
 * Simplified function value for the stub interpreter
 */
data class SimpleFunkFunction(
    val parameters: List<String>,
    val body: Expression,
    val closure: Map<String, FunkValue>,
    val name: String? = null
) : FunkValue() {
    override fun getDisplayString(): String = name ?: "<function>"
}
