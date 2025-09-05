package com.funk.language.interpreter

import com.funk.language.parser.FunkParserImpl
import com.funk.language.parser.Program
import com.funk.language.typechecker.TypeChecker
import com.funk.language.typechecker.TypedProgram
import com.oracle.truffle.api.frame.FrameDescriptor

/**
 * Simple direct interpreter interface that bypasses the polyglot API
 * for easier integration with the CLI.
 */
class FunkInterpreter {
    
    private val parser = FunkParserImpl()
    private val typeChecker = TypeChecker()
    private val converter = ASTToTruffleConverter()
    
    /**
     * Parse, typecheck, and execute a Funk program from source code
     */
    fun execute(source: String): FunkValue {
        try {
            // Parse the source code
            val ast = parser.parse(source)
            
            // Type check the program
            val typedProgram = typeChecker.typeCheck(ast)
            
            // Convert to Truffle nodes and execute
            return executeProgram(typedProgram)
            
        } catch (e: Exception) {
            throw FunkException("Execution error: ${e.message}", e, null)
        }
    }
    
    /**
     * Execute a typed program
     */
    private fun executeProgram(program: TypedProgram): FunkValue {
        // For now, we'll create a simple execution context
        // In a full implementation, this would use proper Truffle frames
        
        val context = FunkContext(
            FunkLanguage(), // We'll need to create a dummy language instance
            null // Dummy environment for now
        )
        
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
    private fun executeItem(item: com.funk.language.typechecker.TypedItem, context: FunkContext): FunkValue {
        return when (item) {
            is com.funk.language.typechecker.TypedLetBinding -> {
                // For now, we'll just evaluate the expression and store it in context
                val value = evaluateExpression(item.expression.expression, context)
                context.defineGlobal(item.variableName, value)
                value
            }
            is com.funk.language.typechecker.TypedTypeDeclaration -> {
                // Type declarations don't produce runtime values
                FunkNull
            }
            is com.funk.language.typechecker.TypedEffectDeclaration -> {
                // Effect declarations don't produce runtime values
                FunkNull
            }
            is com.funk.language.typechecker.TypedModuleDeclaration -> {
                // Module declarations don't produce runtime values
                FunkNull
            }
            else -> {
                throw FunkException.runtimeError("Unsupported program item: ${item::class.simpleName}")
            }
        }
    }
    
    /**
     * Evaluate an expression (simplified version)
     */
    private fun evaluateExpression(expr: com.funk.language.parser.Expression, context: FunkContext): FunkValue {
        return when (expr) {
            is com.funk.language.parser.Literal<*> -> {
                when (val value = expr.value) {
                    is Int -> FunkInt(value.toLong())
                    is Long -> FunkInt(value)
                    is Float -> FunkFloat(value.toDouble())
                    is Double -> FunkFloat(value)
                    is String -> FunkString(value)
                    is Boolean -> FunkBoolean(value)
                    null -> FunkNull
                    else -> throw FunkException.runtimeError("Unsupported literal type: ${value::class.simpleName}")
                }
            }
            is com.funk.language.parser.Identifier -> {
                context.lookupGlobal(expr.name) ?: run {
                    // If it's an uppercase identifier, treat it as a variant constructor
                    if (expr.name.first().isUpperCase()) {
                        FunkVariant(expr.name, null) // Simple constructor with no data
                    } else {
                        throw FunkException.unboundVariable(expr.name)
                    }
                }
            }
            is com.funk.language.parser.BinaryOperation -> {
                val left = evaluateExpression(expr.left, context)
                val right = evaluateExpression(expr.right, context)
                evaluateBinaryOperation(left, expr.operator, right)
            }
            is com.funk.language.parser.RecordLiteral -> {
                val fields = expr.entries.associate { entry ->
                    entry.name to (entry.value?.let { evaluateExpression(it, context) } 
                        ?: context.lookupGlobal(entry.name) 
                        ?: throw FunkException.unboundVariable(entry.name))
                }
                FunkRecord(fields)
            }
            is com.funk.language.parser.MemberAccess -> {
                val record = evaluateExpression(expr.receiver, context)
                if (record is FunkRecord) {
                    record.readMember(expr.member)
                } else {
                    throw FunkException.typeError("Cannot access member '${expr.member}' on ${record::class.simpleName}")
                }
            }
            is com.funk.language.parser.VariantConstruction -> {
                val value = expr.value?.let { evaluateExpression(it, context) }
                FunkVariant(expr.constructor, value)
            }
            is com.funk.language.parser.IfExpression -> {
                val condition = evaluateExpression(expr.condition, context)
                if (condition is FunkBoolean) {
                    if (condition.value) {
                        evaluateExpression(expr.thenBranch, context)
                    } else {
                        evaluateExpression(expr.elseBranch, context)
                    }
                } else {
                    throw FunkException.typeError("If condition must be boolean, got ${condition::class.simpleName}")
                }
            }
            is com.funk.language.parser.RecordUpdate -> {
                val originalRecord = evaluateExpression(expr.record, context)
                if (originalRecord is FunkRecord) {
                    val updatedFields = expr.updates.associate { update ->
                        update.name to (update.value?.let { evaluateExpression(it, context) }
                            ?: context.lookupGlobal(update.name)
                            ?: throw FunkException.unboundVariable(update.name))
                    }
                    // Create new record with updated fields (immutable update)
                    val newFields = originalRecord.fields.toMutableMap()
                    newFields.putAll(updatedFields)
                    FunkRecord(newFields)
                } else {
                    throw FunkException.typeError("Cannot update non-record value: ${originalRecord::class.simpleName}")
                }
            }
            is com.funk.language.parser.LambdaExpression -> {
                FunkFunction(expr.parameters, expr.body, context.captureEnvironment())
            }
            is com.funk.language.parser.FunctionCall -> {
                val function = evaluateExpression(expr.function, context)
                val arguments = evaluateExpression(expr.arguments, context)
                
                if (function is FunkFunction) {
                    callFunction(function, arguments, context)
                } else {
                    throw FunkException.typeError("Cannot call non-function value: ${function::class.simpleName}")
                }
            }
            is com.funk.language.parser.MatchExpression -> {
                val value = evaluateExpression(expr.expression, context)
                evaluateMatchExpression(value, expr.cases, context)
            }
            is com.funk.language.parser.BlockExpression -> {
                val blockContext = context.createChildContext()
                
                // Execute all statements
                for (statement in expr.statements) {
                    executeStatement(statement, blockContext)
                }
                
                // Return the result expression
                evaluateExpression(expr.result, blockContext)
            }
            is com.funk.language.parser.DoExpression -> {
                // Do expressions are similar to block expressions but with effect handling
                val doContext = context.createChildContext()
                
                // Execute all statements
                for (statement in expr.statements) {
                    executeStatement(statement, doContext)
                }
                
                // Return the result expression
                evaluateExpression(expr.result, doContext)
            }
            is com.funk.language.parser.PipeExpression -> {
                // Evaluate left side first
                val leftValue = evaluateExpression(expr.left, context)
                
                // Replace placeholder in right side with left value
                val rightWithPlaceholder = replacePlaceholder(expr.right, leftValue, context)
                evaluateExpression(rightWithPlaceholder, context)
            }
            else -> {
                throw FunkException.runtimeError("Expression evaluation not yet implemented for: ${expr::class.simpleName}")
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
                left is FunkString && right is FunkString -> FunkBoolean(left.value < right.value)
                else -> throw FunkException.typeError("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}")
            }
            ">" -> when {
                left is FunkInt && right is FunkInt -> FunkBoolean(left.value > right.value)
                left is FunkFloat && right is FunkFloat -> FunkBoolean(left.value > right.value)
                left is FunkString && right is FunkString -> FunkBoolean(left.value > right.value)
                else -> throw FunkException.typeError("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}")
            }
            "<=" -> when {
                left is FunkInt && right is FunkInt -> FunkBoolean(left.value <= right.value)
                left is FunkFloat && right is FunkFloat -> FunkBoolean(left.value <= right.value)
                left is FunkString && right is FunkString -> FunkBoolean(left.value <= right.value)
                else -> throw FunkException.typeError("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}")
            }
            ">=" -> when {
                left is FunkInt && right is FunkInt -> FunkBoolean(left.value >= right.value)
                left is FunkFloat && right is FunkFloat -> FunkBoolean(left.value >= right.value)
                left is FunkString && right is FunkString -> FunkBoolean(left.value >= right.value)
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
            left is FunkRecord && right is FunkRecord -> recordsEqual(left, right)
            left is FunkVariant && right is FunkVariant -> variantsEqual(left, right)
            else -> false
        }
    }
    
    private fun recordsEqual(left: FunkRecord, right: FunkRecord): Boolean {
        if (left.fields.keys != right.fields.keys) return false
        return left.fields.all { (key, value) ->
            val rightValue = right.fields[key] ?: return false
            valuesEqual(value, rightValue)
        }
    }
    
    private fun variantsEqual(left: FunkVariant, right: FunkVariant): Boolean {
        if (left.constructor != right.constructor) return false
        return when {
            left.value == null && right.value == null -> true
            left.value != null && right.value != null -> valuesEqual(left.value, right.value)
            else -> false
        }
    }
    
    /**
     * Call a function with arguments
     */
    private fun callFunction(function: FunkFunction, arguments: FunkValue, context: FunkContext): FunkValue {
        // Create a new context for function execution with captured environment
        val functionContext = context.createChildContext()
        
        // Add captured environment to function context
        function.capturedEnvironment.forEach { (name, value) ->
            functionContext.defineLocal(name, value)
        }
        
        // Bind parameters
        when (val params = function.parameters) {
            is com.funk.language.parser.TypedParameters -> {
                // For typed parameters, we expect a record argument
                if (arguments is FunkRecord) {
                    bindRecordParameters(params.recordType, arguments, functionContext)
                } else {
                    throw FunkException.typeError("Expected record argument for typed function parameters")
                }
            }
            is com.funk.language.parser.UntypedParameters -> {
                // For untyped parameters, we expect a record argument
                if (arguments is FunkRecord) {
                    bindUntypedParameters(params.names, arguments, functionContext)
                } else {
                    throw FunkException.typeError("Expected record argument for untyped function parameters")
                }
            }
        }
        
        // Execute function body
        return evaluateExpression(function.body, functionContext)
    }
    
    private fun bindRecordParameters(recordType: com.funk.language.parser.RecordType, arguments: FunkRecord, context: FunkContext) {
        for (entry in recordType.entries) {
            val value = arguments.fields[entry.name] 
                ?: throw FunkException.runtimeError("Missing argument: ${entry.name}")
            context.defineLocal(entry.name, value)
        }
    }
    
    private fun bindUntypedParameters(paramNames: List<String>, arguments: FunkRecord, context: FunkContext) {
        for (paramName in paramNames) {
            val value = arguments.fields[paramName]
                ?: throw FunkException.runtimeError("Missing argument: $paramName")
            context.defineLocal(paramName, value)
        }
    }
    
    /**
     * Evaluate match expression
     */
    private fun evaluateMatchExpression(value: FunkValue, cases: List<com.funk.language.parser.MatchCase>, context: FunkContext): FunkValue {
        for (case in cases) {
            val matchContext = context.createChildContext()
            if (matchesPattern(value, case.pattern, matchContext)) {
                return evaluateExpression(case.expression, matchContext)
            }
        }
        throw FunkException.runtimeError("No pattern matched the value: ${value.toDisplayString()}")
    }
    
    /**
     * Check if value matches pattern and bind variables
     */
    private fun matchesPattern(value: FunkValue, pattern: com.funk.language.parser.Pattern, context: FunkContext): Boolean {
        return when (pattern) {
            is com.funk.language.parser.WildcardPattern -> true
            is com.funk.language.parser.VariablePattern -> {
                context.defineLocal(pattern.name, value)
                true
            }
            is com.funk.language.parser.ConstructorPattern -> {
                if (value is FunkVariant && value.constructor == pattern.constructor) {
                    pattern.recordPattern?.let { recordPattern ->
                        value.value?.let { variantValue ->
                            if (variantValue is FunkRecord) {
                                matchesRecordPattern(variantValue, recordPattern, context)
                            } else false
                        } ?: false
                    } ?: (value.value == null)
                } else false
            }
            is com.funk.language.parser.RecordPattern -> {
                if (value is FunkRecord) {
                    matchesRecordPattern(value, pattern, context)
                } else false
            }
        }
    }
    
    private fun matchesRecordPattern(record: FunkRecord, pattern: com.funk.language.parser.RecordPattern, context: FunkContext): Boolean {
        return pattern.entries.all { entry ->
            val fieldValue = record.fields[entry.name] ?: return false
            entry.pattern?.let { fieldPattern ->
                matchesPattern(fieldValue, fieldPattern, context)
            } ?: run {
                // No pattern means bind the field value to the field name
                context.defineLocal(entry.name, fieldValue)
                true
            }
        }
    }
    
    /**
     * Execute a statement
     */
    private fun executeStatement(statement: com.funk.language.parser.Statement, context: FunkContext): FunkValue {
        return when (statement) {
            is com.funk.language.parser.LetBinding -> {
                val value = evaluateExpression(statement.expression, context)
                context.defineLocal(statement.variableName, value)
                value
            }
            is com.funk.language.parser.ExpressionStatement -> {
                evaluateExpression(statement.expression, context)
            }
            is com.funk.language.parser.ResumeStatement -> {
                // For now, just evaluate the expression (proper effect handling would be more complex)
                evaluateExpression(statement.expression, context)
            }
            else -> {
                throw FunkException.runtimeError("Unsupported statement type: ${statement::class.simpleName}")
            }
        }
    }
    
    /**
     * Replace placeholder with value in pipe expressions
     */
    private fun replacePlaceholder(expr: com.funk.language.parser.Expression, value: FunkValue, context: FunkContext): com.funk.language.parser.Expression {
        // For now, we'll implement a simple version that handles basic cases
        // A full implementation would need to traverse the entire expression tree
        return when (expr) {
            is com.funk.language.parser.PlaceholderExpression -> {
                // Create a temporary identifier for the value
                val tempName = "_pipe_${System.nanoTime()}"
                context.defineLocal(tempName, value)
                com.funk.language.parser.Identifier(tempName)
            }
            is com.funk.language.parser.FunctionCall -> {
                // Replace placeholder in arguments
                val newArgs = replacePlaceholder(expr.arguments, value, context)
                com.funk.language.parser.FunctionCall(expr.function, newArgs)
            }
            else -> expr // Return as-is for other expressions
        }
    }
}
