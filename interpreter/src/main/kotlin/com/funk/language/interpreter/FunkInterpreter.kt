package com.funk.language.interpreter

import com.funk.language.parser.FunkParserImpl
import com.funk.language.ast.*
import com.funk.language.typechecker.TypeChecker
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

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
        
        // Register built-in native functions
        registerNativeFunctions(context)
        
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
                // Handle recursive functions by pre-declaring the variable
                if (item.expression is LambdaExpression) {
                    // For lambda expressions, create a placeholder first for recursion
                    val recursivePlaceholder = RecursiveFunctionPlaceholder(item.variableName)
                    context[item.variableName] = recursivePlaceholder
                    
                    // Now evaluate the lambda with the placeholder in context
                    val value = evaluateExpression(item.expression, context)
                    
                    // Replace the placeholder with the actual function
                    context[item.variableName] = value
                    
                    // If it's a user function, set up recursive reference
                    if (value is SimpleFunkFunction) {
                        value.setupRecursiveReference(item.variableName, value)
                    }
                    
                    value
                } else {
                    // Non-function case: check for self-reference (which should be forbidden)
                    if (containsSelfReference(item.expression, item.variableName)) {
                        throw RuntimeException("Variable '${item.variableName}' cannot reference itself in its own definition. Self-reference is only allowed for functions.")
                    }
                    
                    // Evaluate normally without the variable in context
                    val value = evaluateExpression(item.expression, context)
                    context[item.variableName] = value
                    value
                }
            }
            is TypeDeclaration -> {
                // Type declarations don't produce runtime values
                FunkNull
            }
            is EffectDeclaration -> {
                // Effect declarations create a record type with operation signatures
                // This allows Log.print to be resolved as a member access
                val operations = item.operations.associate { op ->
                    op.name to FunkNull // Placeholder - will be replaced by handlers
                }
                val effectRecord = FunkRecord(operations)
                context[item.name] = effectRecord
                effectRecord
            }
            is ImplDeclaration -> {
                if (item.handlerParams == null) {
                    // Non-parameterized implementation - create handler directly
                    createDirectHandler(item, context)
                } else {
                    // Parameterized implementation - create handler factory
                    createHandlerFactory(item, context)
                }
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
        // println("DEBUG: Evaluating expression: ${expr::class.simpleName}")
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
                        throw UndefinedVariableException(expr.name)
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
            is MatchExpression -> {
                val value = evaluateExpression(expr.expression, context)
                evaluateMatchExpression(value, expr.cases, context)
            }
            is DoExpression -> {
                // Check if this do block contains effect calls
                if (containsEffectCalls(expr)) {
                    // Execute as state machine for async effects
                    executeDoBlockAsStateMachine(expr, context)
                } else {
                    // Execute sequentially for pure computations
                    executeDoBlockSequentially(expr, context)
                }
            }
            is FunctionCall -> {
                // Check if this is an effect call (function is a member access like Log.log)
                if (expr.function is MemberAccess) {
                    val memberAccess = expr.function as MemberAccess
                    if (memberAccess.receiver is Identifier) {
                        val effectName = (memberAccess.receiver as Identifier).name
                        val operationName = memberAccess.member
                        
                        // Check if this is a known effect
                        if (context.containsKey(effectName) && context[effectName] is FunkRecord) {
                            // Convert to effect call
                            val effectCall = EffectCallExpression(effectName, operationName, expr.arguments)
                            return executeEffectCall(effectCall, context)
                        }
                    }
                }
                
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
                SimpleFunkFunction(paramNames, expr.body, context.toMap(), inferredType = expr.inferredType) // Capture current context and type
            }
            is DoExpression -> {
                // Check if this do block contains effect calls - if so, lower to state machine
                if (containsEffectCalls(expr)) {
                    executeDoBlockAsStateMachine(expr, context)
                } else {
                    // Simple sequential execution for pure do blocks
                    var result: FunkValue = FunkNull
                    for (stmt in expr.statements) {
                        result = executeStatement(stmt, context)
                    }
                    evaluateExpression(expr.result, context)
                }
            }
            is PerformExpression -> {
                // Execute expression with provided handlers
                // Process handlers in reverse order (last to first) to match semantics:
                // perform A, B, C do expr  =>  A handles outermost, C handles innermost
                val handlerMap = mutableMapOf<String, FunkValue>()
                
                expr.handlers.reversed().forEach { performHandler ->
                    val handlerName = performHandler.name
                    val handlerOrFactory = context[handlerName] 
                        ?: throw HandlerNotFoundException(handlerName)
                    
                    val handler = when (handlerOrFactory) {
                        is FunkRecord -> {
                            // Direct handler (non-parameterized)
                            handlerOrFactory
                        }
                        is HandlerFactory -> {
                            // Parameterized handler - instantiate with provided parameters
                            val params = performHandler.parameters?.let { recordLit ->
                                evaluateExpression(recordLit, context) as FunkRecord
                            } ?: FunkRecord(emptyMap())
                            handlerOrFactory.createHandler(params)
                        }
                        else -> {
                            throw RuntimeException("Invalid handler type: ${handlerOrFactory::class.simpleName}")
                        }
                    }
                    
                    // Map handler to its effect based on impl declarations
                    val effectName = findEffectForHandler(handlerName, context)
                    handlerMap[effectName] = handler
                }
                
                // Set up handlers in context before evaluating the expression
                val originalHandlers = context["__handlers"]
                val existingHandlers = (originalHandlers as? FunkRecord)?.fields ?: emptyMap()
                val mergedHandlers = existingHandlers.toMutableMap()
                mergedHandlers.putAll(handlerMap)
                context["__handlers"] = FunkRecord(mergedHandlers)
                
                try {
                    // Evaluate the expression with handlers available
                    val result = evaluateExpression(expr.expression, context)
                    
                    // If it's a function, wrap it with handlers for future calls
                    when (result) {
                        is SimpleFunkFunction -> {
                            // Create a new function that carries the handlers in its closure
                            val newClosure = result.closure.toMutableMap()
                            newClosure["__handlers"] = FunkRecord(mergedHandlers)
                            
                            // Remove handled effects from the function type
                            val newInferredType = when (val originalType = result.inferredType) {
                                is com.funk.language.ast.FunkType.FunctionType -> {
                                    val remainingEffects = originalType.effects?.effects?.toMutableSet() ?: mutableSetOf()
                                    handlerMap.keys.forEach { effectName ->
                                        remainingEffects.remove(effectName)
                                    }
                                    val newEffectRow = if (remainingEffects.isEmpty()) null else com.funk.language.ast.FunkType.EffectRow(remainingEffects)
                                    originalType.copy(effects = newEffectRow)
                                }
                                else -> originalType
                            }
                            
                            SimpleFunkFunction(
                                parameters = result.parameters,
                                body = result.body,
                                closure = newClosure,
                                name = result.name,
                                inferredType = newInferredType
                            )
                        }
                        else -> {
                            // For non-functions (including direct effect call results), return as-is
                            result
                        }
                    }
                } finally {
                    // Restore original handlers
                    if (originalHandlers != null) {
                        context["__handlers"] = originalHandlers
                    } else {
                        context.remove("__handlers")
                    }
                }
            }
            is EffectCallExpression -> {
                // Perform effect call
                executeEffectCall(expr, context)
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
                else -> throw ArithmeticException("+", left::class.simpleName ?: "Unknown", right::class.simpleName ?: "Unknown")
            }
            "++" -> when {
                left is FunkString && right is FunkString -> FunkString(left.value + right.value)
                else -> throw ArithmeticException("++", left::class.simpleName ?: "Unknown", right::class.simpleName ?: "Unknown")
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
            "<=" -> when {
                left is FunkInt && right is FunkInt -> FunkBoolean(left.value <= right.value)
                left is FunkFloat && right is FunkFloat -> FunkBoolean(left.value <= right.value)
                else -> throw RuntimeException("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}")
            }
            ">=" -> when {
                left is FunkInt && right is FunkInt -> FunkBoolean(left.value >= right.value)
                left is FunkFloat && right is FunkFloat -> FunkBoolean(left.value >= right.value)
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
        if (operator == "-") {
            if (operand is FunkInt) {
                return FunkInt(-operand.value)
            } else if (operand is FunkFloat) {
                return FunkFloat(-operand.value)
            } else {
                throw RuntimeException("Cannot negate ${operand::class.simpleName}")
            }
        } else if (operator == "!") {
            return FunkBoolean(!isTruthy(operand))
        } else {
            throw RuntimeException("Unknown unary operator: $operator")
        }
    }
    
    /**
     * Call a function with arguments
     */
    /**
     * Call a function with record arguments, supporting partial application
     */
    private fun callFunctionWithRecord(function: FunkValue, arguments: FunkRecord, context: MutableMap<String, FunkValue>): FunkValue {
        // println("DEBUG: callFunctionWithRecord called with function type: ${function::class.simpleName}")
        return when (function) {
            is NativeFunkFunction -> {
                // Call native function directly
                function.call(arguments.fields)
            }
            is SimpleFunkFunction -> {
                val providedParams = arguments.fields.keys
                val requiredParams = function.parameters.toSet()
                val missingParams = requiredParams - providedParams
                
                if (missingParams.isEmpty()) {
                    // All parameters provided - check for unhandled effects before execution
                    if (function.hasUnhandledEffects()) {
                        val unhandledEffects = function.getUnhandledEffects()
                        val availableHandlers = (context["__handlers"] as? FunkRecord)?.fields?.keys ?: emptySet()
                        val actuallyUnhandledEffects = unhandledEffects - availableHandlers
                        
                        if (actuallyUnhandledEffects.isNotEmpty()) {
                            throw FunkException(
                                "Cannot execute function with unhandled effects: ${actuallyUnhandledEffects.joinToString(", ")}. " +
                                "Use 'perform ... do' to provide handlers for these effects."
                            )
                        }
                    }
                    
                    // Execute function
                    val newContext = function.closure.toMutableMap()
                    
                    // Add recursive reference if this is a recursive function
                    function.recursiveName?.let { name ->
                        newContext[name] = function.recursiveFunction ?: function
                    }
                    
                    // Bind all parameters
                    for (param in function.parameters) {
                        newContext[param] = arguments.fields[param] ?: FunkNull
                    }
                    // Propagate effect handlers from current context
                    context["__handlers"]?.let { 
                        newContext["__handlers"] = it 
                    }
                    
                    // Execute with tail call optimization
                    executeWithTailCallOptimization(function, newContext)
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
            is NativeFunkFunction -> {
                // For native functions, convert args list to record if needed
                val argsRecord = if (args.size == 1 && args[0] is FunkRecord) {
                    args[0] as FunkRecord
                } else {
                    // Convert list to record with generic names
                    FunkRecord(args.mapIndexed { index, value -> "arg$index" to value }.toMap())
                }
                function.call(argsRecord.fields)
            }
            is SimpleFunkFunction -> {
                // RUNTIME EFFECT VALIDATION: Check if function has unhandled effects
                if (function.hasUnhandledEffects()) {
                    val unhandledEffects = function.getUnhandledEffects()
                    val availableHandlers = (context["__handlers"] as? FunkRecord)?.fields?.keys ?: emptySet()
                    val actuallyUnhandledEffects = unhandledEffects - availableHandlers
                    
                    if (actuallyUnhandledEffects.isNotEmpty()) {
                        throw FunkException(
                            "Cannot execute function with unhandled effects: ${actuallyUnhandledEffects.joinToString(", ")}. " +
                            "Use 'perform ... do' to provide handlers for these effects."
                        )
                    }
                }
                
                        // Create new context with captured closure and parameters
                        val newContext = function.closure.toMutableMap()
                        
                        // Bind parameters (simplified - assume list of parameter names)
                        function.parameters.forEachIndexed { index, param ->
                            if (index < args.size) {
                                newContext[param] = args[index]
                            }
                        }
                        
                        // Propagate effect handlers from current context
                        context["__handlers"]?.let { 
                            newContext["__handlers"] = it 
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
    
    /**
     * Check if a do expression contains effect calls
     */
    private fun containsEffectCalls(expr: DoExpression): Boolean {
        // Check statements
        for (stmt in expr.statements) {
            when (stmt) {
                is LetStatement -> if (containsEffectCallsInExpression(stmt.expression)) return true
                is ExpressionStatement -> if (containsEffectCallsInExpression(stmt.expression)) return true
            }
        }
        // Check result expression
        return containsEffectCallsInExpression(expr.result)
    }
    
    /**
     * Check if an expression contains effect calls
     */
    private fun containsEffectCallsInExpression(expr: Expression): Boolean {
        return when (expr) {
            is EffectCallExpression -> true
            is DoExpression -> containsEffectCalls(expr)
            is FunctionCall -> containsEffectCallsInExpression(expr.function) || containsEffectCallsInExpression(expr.arguments)
            is BinaryOperation -> containsEffectCallsInExpression(expr.left) || containsEffectCallsInExpression(expr.right)
            is IfExpression -> containsEffectCallsInExpression(expr.condition) || 
                               containsEffectCallsInExpression(expr.thenBranch) || 
                               containsEffectCallsInExpression(expr.elseBranch)
            is MatchExpression -> containsEffectCallsInExpression(expr.expression) || 
                                  expr.cases.any { containsEffectCallsInExpression(it.expression) }
            is RecordLiteral -> expr.entries.any { it.value?.let { v -> containsEffectCallsInExpression(v) } ?: false }
            is LambdaExpression -> containsEffectCallsInExpression(expr.body)
            is MemberAccess -> containsEffectCallsInExpression(expr.receiver)
            is RecordUpdateExpression -> containsEffectCallsInExpression(expr.baseRecord) || 
                                         expr.updates.any { containsEffectCallsInExpression(it.value) }
            is PerformExpression -> containsEffectCallsInExpression(expr.expression)
            else -> false
        }
    }
    
    /**
     * Execute an effect call
     */
    private fun executeEffectCall(expr: EffectCallExpression, context: MutableMap<String, FunkValue>): FunkValue {
        // Look up handler from context
        val handlersRecord = context["__handlers"] as? FunkRecord
            ?: throw RuntimeException("No handlers available for effect call")
        val handlers = handlersRecord.fields
        
        val handler = handlers[expr.effectName]
            ?: throw RuntimeException("No handler found for effect: ${expr.effectName}")
        
        if (handler !is FunkRecord) {
            throw RuntimeException("Handler must be a record of operations")
        }
        
        val operation = handler.fields[expr.operationName]
            ?: throw RuntimeException("Handler ${expr.effectName} has no operation ${expr.operationName}")
        
        if (operation !is SimpleFunkFunction && operation !is NativeFunkFunction) {
            throw RuntimeException("Effect operation must be a function, got: ${operation::class.simpleName}")
        }
        
        // Execute the handler operation
        val arguments = evaluateExpression(expr.arguments, context)
        
        return when (operation) {
            is NativeFunkFunction -> {
                // Native functions handle their own argument processing
                val argsMap = when (arguments) {
                    is FunkRecord -> arguments.fields
                    else -> throw RuntimeException("Native function expects record arguments, got: ${arguments::class.simpleName}")
                }
                operation.call(argsMap)
            }
            is SimpleFunkFunction -> {
                // Regular Funk functions use the standard call mechanism
                if (arguments is FunkRecord) {
                    callFunctionWithRecord(operation, arguments, context)
                } else {
                    callFunction(operation, listOf(arguments), context)
                }
            }
            else -> throw RuntimeException("Unknown function type: ${operation::class.simpleName}")
        }
    }
    
    
    /**
     * Execute a do block as a state machine (for effectful code)
     */
    private fun executeDoBlockAsStateMachine(expr: DoExpression, context: MutableMap<String, FunkValue>): FunkValue {
        // Create a state machine for this do block
        val stateMachine = StateMachine.create(
            localVariables = context.toMutableMap(),
            effectContext = EffectContext(
                handlers = extractEffectHandlers(context),
                currentHandler = context["__current_handler"] as? FunkRecord
            )
        )
        
        // Compile do block into state machine states
        val states = compileDoBlockToStates(expr)
        
        // Execute state machine with Loom virtual thread
        return executeStateMachineAsync(stateMachine, states, context)
    }
    
    /**
     * Execute a do block sequentially (for pure computations)
     */
    private fun executeDoBlockSequentially(expr: DoExpression, context: MutableMap<String, FunkValue>): FunkValue {
        val localContext = context.toMutableMap()
        
        // Execute statements
        for (stmt in expr.statements) {
            executeStatement(stmt, localContext)
        }
        
        // Execute result expression
        return evaluateExpression(expr.result, localContext)
    }
    
    /**
     * Execute a state machine with async support using Project Loom
     */
    private fun executeStateMachine(stateMachine: StateMachine, expr: DoExpression, context: MutableMap<String, FunkValue>): FunkValue {
        var currentMachine = stateMachine
        val localContext = currentMachine.localVariables
        
        // Execute statements sequentially, handling async effects
        for ((index, stmt) in expr.statements.withIndex()) {
            when (stmt) {
                is LetStatement -> {
                    // Check if the expression contains async effects
                    if (containsEffectCallsInExpression(stmt.expression)) {
                        val result = executeExpressionWithAsyncSupport(stmt.expression, localContext, currentMachine)
                        localContext[stmt.variableName] = result
                    } else {
                        val result = evaluateExpression(stmt.expression, localContext)
                        localContext[stmt.variableName] = result
                    }
                }
                is ExpressionStatement -> {
                    if (containsEffectCallsInExpression(stmt.expression)) {
                        executeExpressionWithAsyncSupport(stmt.expression, localContext, currentMachine)
                    } else {
                        evaluateExpression(stmt.expression, localContext)
                    }
                }
            }
        }
        
        // Execute result expression
        return if (containsEffectCallsInExpression(expr.result)) {
            executeExpressionWithAsyncSupport(expr.result, localContext, currentMachine)
        } else {
            evaluateExpression(expr.result, localContext)
        }
    }
    
    /**
     * Execute an expression with async effect support using virtual threads
     */
    private fun executeExpressionWithAsyncSupport(
        expr: Expression, 
        context: MutableMap<String, FunkValue>,
        stateMachine: StateMachine
    ): FunkValue {
        return when (expr) {
            is EffectCallExpression -> {
                executeAsyncEffectCall(expr, context, stateMachine)
            }
            is DoExpression -> {
                executeStateMachine(stateMachine, expr, context)
            }
            else -> {
                // For non-effect expressions, execute normally
                evaluateExpression(expr, context)
            }
        }
    }
    
    /**
     * Execute an effect call with async support
     */
    private fun executeAsyncEffectCall(
        expr: EffectCallExpression,
        context: MutableMap<String, FunkValue>,
        stateMachine: StateMachine
    ): FunkValue {
        // Look up handler from context
        val handlersRecord = context["__handlers"] as? FunkRecord
            ?: throw RuntimeException("No handlers available for effect call")
        val handlers = handlersRecord.fields
        
        val handler = handlers[expr.effectName]
            ?: throw RuntimeException("No handler found for effect: ${expr.effectName}")
        
        // Check if handler supports async execution
        if (handler is AsyncEffectHandler && handler.canHandle(expr.effectName)) {
            // Execute asynchronously using virtual threads
            val arguments = evaluateExpression(expr.arguments, context)
            val future = VirtualThreadExecutor.submitSuspending {
                handler.handle(expr.operationName, arguments)
            }
            
            // Use virtual thread to wait for result without blocking OS threads
            return try {
                // This uses Project Loom's virtual threads via VirtualThreadExecutor
                future.get()
            } catch (e: Exception) {
                throw RuntimeException("Async effect execution failed: ${e.message}", e)
            }
        } else {
            // Fall back to synchronous execution
            return executeEffectCall(expr, context)
        }
    }
    
    /**
     * Extract effect handlers from context
     */
    private fun extractEffectHandlers(context: MutableMap<String, FunkValue>): Map<String, FunkRecord> {
        val handlers = mutableMapOf<String, FunkRecord>()
        val handlersValue = context["__handlers"] as? FunkRecord
        if (handlersValue != null) {
            handlersValue.fields.forEach { (name, value) ->
                if (value is FunkRecord) {
                    handlers[name] = value
                }
            }
        }
        return handlers
    }
    
    /**
     * Compile a do block into state machine states
     */
    private fun compileDoBlockToStates(expr: DoExpression): List<StateMachineState> {
        val states = mutableListOf<StateMachineState>()
        
        // Each statement becomes a state
        expr.statements.forEachIndexed { index, statement ->
            when (statement) {
                is LetStatement -> {
                    states.add(StateMachineState.LetState(index, statement.variableName, statement.expression))
                }
                is ExpressionStatement -> {
                    if (containsEffectCallsInExpression(statement.expression)) {
                        // Effect call - this is a suspension point
                        states.add(StateMachineState.EffectState(index, statement.expression))
                    } else {
                        // Pure expression
                        states.add(StateMachineState.ExpressionState(index, statement.expression))
                    }
                }
            }
        }
        
        // Final result expression
        states.add(StateMachineState.ResultState(expr.statements.size, expr.result))
        
        return states
    }
    
    /**
     * Execute state machine asynchronously using Loom virtual threads
     */
    private fun executeStateMachineAsync(
        initialStateMachine: StateMachine, 
        states: List<StateMachineState>, 
        context: MutableMap<String, FunkValue>
    ): FunkValue {
        // Use virtual thread executor for async effect handling
        val virtualThreadExecutor = VirtualThreadExecutor()
        var currentMachine = initialStateMachine
        var result: FunkValue = FunkNull
        
        for (state in states) {
            when (state) {
                is StateMachineState.LetState -> {
                    val value = evaluateExpression(state.expression, currentMachine.localVariables)
                    currentMachine.localVariables[state.variableName] = value
                }
                is StateMachineState.ExpressionState -> {
                    evaluateExpression(state.expression, currentMachine.localVariables)
                }
                is StateMachineState.EffectState -> {
                    // This is a suspension point - execute effect call asynchronously
                    result = executeEffectStateAsync(state, currentMachine, virtualThreadExecutor)
                }
                is StateMachineState.ResultState -> {
                    result = evaluateExpression(state.expression, currentMachine.localVariables)
                }
            }
        }
        
        return result
    }
    
    /**
     * Execute an effect state asynchronously with suspension/resumption
     */
    private fun executeEffectStateAsync(
        state: StateMachineState.EffectState,
        stateMachine: StateMachine,
        executor: VirtualThreadExecutor
    ): FunkValue {
        // Check if this is an effect call that needs async handling
        if (state.expression is EffectCallExpression) {
            // This is a suspension point - suspend the virtual thread
            return suspendAndExecuteEffect(state.expression, stateMachine, executor)
        } else {
            // Check if expression contains effect calls (like member access that becomes effect call)
            if (containsEffectCallsInExpression(state.expression)) {
                // Convert member access to effect call and suspend
                val effectCall = convertToEffectCall(state.expression, stateMachine.localVariables)
                if (effectCall != null) {
                    return suspendAndExecuteEffect(effectCall, stateMachine, executor)
                }
            }
            
            // Not an effect call, execute normally
            return evaluateExpression(state.expression, stateMachine.localVariables)
        }
    }
    
    /**
     * Suspend virtual thread and execute effect with continuation
     */
    private fun suspendAndExecuteEffect(
        effectCall: EffectCallExpression,
        stateMachine: StateMachine,
        executor: VirtualThreadExecutor
    ): FunkValue {
        // Look up handler from context
        val handlersRecord = stateMachine.localVariables["__handlers"] as? FunkRecord
            ?: throw RuntimeException("No handlers available for effect call")
        val handlers = handlersRecord.fields
        
        val handler = handlers[effectCall.effectName]
            ?: throw RuntimeException("No handler found for effect: ${effectCall.effectName}")
        
        if (handler !is FunkRecord) {
            throw RuntimeException("Handler must be a record of operations")
        }
        
        val operation = handler.fields[effectCall.operationName] as? SimpleFunkFunction
            ?: throw RuntimeException("Operation ${effectCall.operationName} not found in handler")
        
        // Execute the handler operation (this may involve async work)
        val arguments = evaluateExpression(effectCall.arguments, stateMachine.localVariables)
        
        // For now, execute synchronously but with proper structure for future Loom integration
        // In full Loom implementation, this would suspend the virtual thread
        return when (arguments) {
            is FunkRecord -> callFunction(operation, listOf(arguments), stateMachine.localVariables)
            else -> callFunction(operation, listOf(arguments), stateMachine.localVariables)
        }
    }
    
    /**
     * Convert expressions like Log.log to EffectCallExpression
     */
    private fun convertToEffectCall(expression: Expression, context: MutableMap<String, FunkValue>): EffectCallExpression? {
        return when (expression) {
            is FunctionCall -> {
                if (expression.function is MemberAccess) {
                    val memberAccess = expression.function as MemberAccess
                    if (memberAccess.receiver is Identifier) {
                        val effectName = (memberAccess.receiver as Identifier).name
                        val operationName = memberAccess.member
                        
                        // Check if this is a known effect
                        if (context.containsKey(effectName) && context[effectName] is FunkRecord) {
                            return EffectCallExpression(effectName, operationName, expression.arguments)
                        }
                    }
                }
                null
            }
            else -> null
        }
    }
    
    /**
     * Find which effect a handler implements based on stored impl mappings
     */
    private fun findEffectForHandler(handlerName: String, context: MutableMap<String, FunkValue>): String {
        val implMappings = context["__impl_mappings"] as? FunkRecord
        val effectNameValue = implMappings?.fields?.get(handlerName) as? FunkString
        return effectNameValue?.value ?: handlerName // Fallback to handler name
    }
    
    /**
     * Create a direct handler for non-parameterized implementations
     */
    private fun createDirectHandler(item: ImplDeclaration, context: MutableMap<String, FunkValue>): FunkValue {
        val operations = item.operations.associate { op ->
            // Extract parameter names from the record type annotation
            val paramNames = when (val paramType = op.paramType) {
                is RecordTypeAnnotation -> {
                    // Extract field names from record type: {msg: String, level: Int} -> ["msg", "level"]
                    paramType.entries.map { it.name }
                }
                is IdentifierTypeAnnotation -> {
                    // Single parameter: String -> ["value"]
                    listOf("value")
                }
                else -> {
                    // Fallback for complex types
                    listOf("args")
                }
            }
            
            op.name to SimpleFunkFunction(
                paramNames,
                // If implementation is a lambda, extract its body
                when (val impl = op.implementation) {
                    is LambdaExpression -> impl.body
                    else -> impl
                },
                context.toMap()
            )
        }
        val handler = FunkRecord(operations)
        context[item.handlerName] = handler
        
        // Store the impl mapping for later lookup
        @Suppress("UNCHECKED_CAST")
        val implMappings = context.getOrPut("__impl_mappings") { 
            FunkRecord(mutableMapOf<String, FunkValue>()) 
        } as FunkRecord
        implMappings.fields as MutableMap<String, FunkValue>
        (implMappings.fields as MutableMap)[item.handlerName] = FunkString(item.effectName)
        
        return handler
    }
    
    /**
     * Create a handler factory for parameterized implementations
     */
    private fun createHandlerFactory(item: ImplDeclaration, context: MutableMap<String, FunkValue>): FunkValue {
        // Extract parameter names from handler params
        val handlerParamNames = item.handlerParams!!.entries.map { it.name }
        
        // Create a special handler factory that can be called to create instances
        val factory = HandlerFactory(item, context.toMap())
        
        // Store factory under handler name
        context[item.handlerName] = factory
        
        // Store the impl mapping
        @Suppress("UNCHECKED_CAST")
        val implMappings = context.getOrPut("__impl_mappings") { 
            FunkRecord(mutableMapOf<String, FunkValue>()) 
        } as FunkRecord
        (implMappings.fields as MutableMap)[item.handlerName] = FunkString(item.effectName)
        
        return factory
    }
    
    /**
     * Handler factory for parameterized implementations
     */
    class HandlerFactory(
        private val implDeclaration: ImplDeclaration,
        private val capturedContext: Map<String, FunkValue>
    ) : FunkValue() {
        override fun getDisplayString(): String = "<factory-${implDeclaration.handlerName}>"
        
        fun createHandler(params: FunkRecord): FunkRecord {
            // Create operations with access to handler parameters
            val operations = implDeclaration.operations.associate { op ->
                // Extract parameter names from the operation type
                val paramNames = when (val paramType = op.paramType) {
                    is RecordTypeAnnotation -> paramType.entries.map { it.name }
                    is IdentifierTypeAnnotation -> listOf("value")
                    else -> listOf("args")
                }
                
                // Create function with access to both operation params and handler params
                val combinedContext = capturedContext.toMutableMap()
                params.fields.forEach { (name, value) ->
                    combinedContext[name] = value
                }
                
                op.name to SimpleFunkFunction(
                    paramNames,
                    when (val impl = op.implementation) {
                        is LambdaExpression -> impl.body
                        else -> impl
                    },
                    combinedContext
                )
            }
            
            return FunkRecord(operations)
        }
    }
    
    /**
     * Native function that can be called from Funk code
     */
    
    /**
     * Register native functions for built-in effects
     */
    private fun registerNativeFunctions(context: MutableMap<String, FunkValue>) {
        // Use the centralized native functions registry
        NativeFunctions.registerAll(context)
        
        // Create built-in effect handlers
        context["NativeConsole"] = FunkRecord(mapOf(
            "print" to context["native_print"]!!,
            "debug" to context["native_debug"]!!
        ))
        
        context["NativeRandom"] = FunkRecord(mapOf(
            "nextInt" to context["native_random_int"]!!,
            "nextDouble" to context["native_random_double"]!!
        ))
    }
    /**
     * Execute function with tail call optimization
     */
    private fun executeWithTailCallOptimization(
        function: SimpleFunkFunction,
        initialContext: MutableMap<String, FunkValue>
    ): FunkValue {
        var currentContext = initialContext
        var currentFunction = function
        
        // Tail call optimization loop
        while (true) {
            // Mark this context as being in tail position for optimization
            currentContext["__in_tail_position"] = FunkBoolean(true)
            
            val result = evaluateExpressionWithTailCallDetection(currentFunction.body, currentContext, currentFunction)
            
            // Check if result is a tail call
            if (result is TailCall && result.function == currentFunction) {
                // This is a tail recursive call - optimize it
                currentContext = currentFunction.closure.toMutableMap()
                
                // Add recursive reference
                currentFunction.recursiveName?.let { name ->
                    currentContext[name] = currentFunction
                }
                
                // Bind new parameters from tail call
                when (result.args) {
                    is FunkRecord -> {
                        for (param in currentFunction.parameters) {
                            currentContext[param] = result.args.fields[param] ?: FunkNull
                        }
                    }
                    else -> {
                        if (currentFunction.parameters.size == 1) {
                            currentContext[currentFunction.parameters[0]] = result.args
                        } else {
                            throw RuntimeException("Function expects ${currentFunction.parameters.size} parameters but got single value")
                        }
                    }
                }
                
                // Continue loop for tail call optimization
            } else {
                // Not a tail call, return the result
                return result
            }
        }
    }
    
    /**
     * Evaluate expression with tail call detection
     */
    private fun evaluateExpressionWithTailCallDetection(
        expr: Expression,
        context: MutableMap<String, FunkValue>,
        currentFunction: SimpleFunkFunction
    ): FunkValue {
        // Check if we're in tail position and this is a recursive call
        val inTailPosition = (context["__in_tail_position"] as? FunkBoolean)?.value ?: false
        
        return when (expr) {
            is FunctionCall -> {
                // Check if this is a tail recursive call
                val function = evaluateExpression(expr.function, context)
                
                if (inTailPosition && function == currentFunction) {
                    // This is a tail recursive call - return TailCall object
                    val arguments = evaluateExpression(expr.arguments, context)
                    TailCall(currentFunction, arguments)
                } else {
                    // Regular function call
                    val argumentsValue = evaluateExpression(expr.arguments, context)
                    if (argumentsValue is FunkRecord) {
                        callFunctionWithRecord(function, argumentsValue, context)
                    } else {
                        val args = listOf(argumentsValue)
                        callFunction(function, args, context)
                    }
                }
            }
            is IfExpression -> {
                // Both branches are in tail position
                val condition = evaluateExpression(expr.condition, context)
                if (isTruthy(condition)) {
                    evaluateExpressionWithTailCallDetection(expr.thenBranch, context, currentFunction)
                } else {
                    evaluateExpressionWithTailCallDetection(expr.elseBranch, context, currentFunction)
                }
            }
            is DoExpression -> {
                // Only the result expression is in tail position
                val localContext = context.toMutableMap()
                
                // Execute statements (not in tail position)
                localContext["__in_tail_position"] = FunkBoolean(false)
                for (stmt in expr.statements) {
                    executeStatement(stmt, localContext)
                }
                
                // Result expression is in tail position
                localContext["__in_tail_position"] = FunkBoolean(true)
                evaluateExpressionWithTailCallDetection(expr.result, localContext, currentFunction)
            }
            else -> {
                // For other expressions, use regular evaluation
                evaluateExpression(expr, context)
            }
        }
    }
    
    /**
     * Check if an expression contains a self-reference to the given variable name
     */
    private fun containsSelfReference(expr: Expression, variableName: String): Boolean {
        return when (expr) {
            is Identifier -> expr.name == variableName
            is BinaryOperation -> containsSelfReference(expr.left, variableName) || containsSelfReference(expr.right, variableName)
            is UnaryOperation -> containsSelfReference(expr.operand, variableName)
            is FunctionCall -> containsSelfReference(expr.function, variableName) || containsSelfReference(expr.arguments, variableName)
            is IfExpression -> containsSelfReference(expr.condition, variableName) || 
                              containsSelfReference(expr.thenBranch, variableName) || 
                              containsSelfReference(expr.elseBranch, variableName)
            is DoExpression -> {
                // Check statements and result
                expr.statements.any { stmt -> 
                    when (stmt) {
                        is LetStatement -> containsSelfReference(stmt.expression, variableName)
                        is ExpressionStatement -> containsSelfReference(stmt.expression, variableName)
                    }
                } || containsSelfReference(expr.result, variableName)
            }
            is RecordLiteral -> expr.entries.any { (_, fieldExpr) -> 
                fieldExpr?.let { containsSelfReference(it, variableName) } ?: false 
            }
            is MemberAccess -> containsSelfReference(expr.receiver, variableName)
            is VariantConstruction -> expr.value?.let { containsSelfReference(it, variableName) } ?: false
            is PerformExpression -> containsSelfReference(expr.expression, variableName)
            is LambdaExpression -> containsSelfReference(expr.body, variableName)
            // Literals don't contain references
            is IntLiteral, is FloatLiteral, is StringLiteral, is BooleanLiteral -> false
            // Other expressions we don't need to check
            else -> false
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
    val name: String? = null,
    val inferredType: FunkType? = null,  // Add type information for effect validation
    var recursiveName: String? = null,
    var recursiveFunction: SimpleFunkFunction? = null
) : FunkValue() {
    override fun getDisplayString(): String = name ?: "<function>"
    
    /**
     * Set up recursive reference for this function
     */
    fun setupRecursiveReference(name: String, function: SimpleFunkFunction) {
        this.recursiveName = name
        this.recursiveFunction = function
    }
    
    /**
     * Check if this function has unhandled effects
     */
    fun hasUnhandledEffects(): Boolean {
        return when (val type = inferredType) {
            is FunkType.FunctionType -> {
                val effects = type.effects
                effects != null && effects.effects.isNotEmpty()
            }
            else -> false
        }
    }
    
    /**
     * Get the unhandled effects of this function
     */
    fun getUnhandledEffects(): Set<String> {
        return when (val type = inferredType) {
            is FunkType.FunctionType -> {
                val effects = type.effects
                effects?.effects ?: emptySet()
            }
            else -> emptySet()
        }
    }
}

