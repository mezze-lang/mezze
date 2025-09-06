package com.funk.language.compiler

import com.funk.language.typechecker.*
import com.funk.language.parser.*

/**
 * Complete JavaScript code generator for Funk language
 */
class JavaScriptCodeGen {
    
    private val indentLevel = mutableListOf<Int>()
    private var currentIndent = 0
    
    fun generateProgram(program: TypedProgram): String {
        val builder = StringBuilder()
        
        // Add comprehensive runtime library
        builder.appendLine(generateRuntime())
        builder.appendLine()
        
        // Generate code for each item
        for (item in program.items) {
            val code = generateItem(item)
            if (code.isNotBlank()) {
                builder.appendLine(code)
            }
        }
        
        // Add main execution if there are expressions
        val hasExpressions = program.items.any { it is TypedLetBinding }
        if (hasExpressions) {
            builder.appendLine()
            builder.appendLine("// Export all bindings for inspection")
            builder.appendLine("if (typeof module !== 'undefined' && module.exports) {")
            for (item in program.items) {
                if (item is TypedLetBinding) {
                    builder.appendLine("  module.exports.${item.variableName} = ${item.variableName};")
                }
            }
            builder.appendLine("}")
        }
        
        return builder.toString()
    }
    
    private fun generateRuntime(): String {
        return """
// Funk Runtime Library - Complete Implementation
const FunkRuntime = {
    // Variant construction and matching
    variant: (constructor, data = null) => ({
        __type: 'variant',
        constructor,
        data,
        toString: () => data ? constructor + '(' + JSON.stringify(data) + ')' : constructor
    }),
    
    // Record operations
    record: (fields) => ({
        __type: 'record',
        ...fields,
        toString: () => '{' + Object.entries(fields).map(([k,v]) => k + ': ' + v).join(', ') + '}'
    }),
    
    updateRecord: (record, updates) => ({
        ...record,
        ...updates
    }),
    
    // Pattern matching support
    match: (value, patterns) => {
        for (const [pattern, handler] of patterns) {
            const result = FunkRuntime.matchPattern(value, pattern);
            if (result.matched) {
                return handler(result.bindings);
            }
        }
        throw new Error('Non-exhaustive pattern match for value: ' + JSON.stringify(value));
    },
    
    matchPattern: (value, pattern) => {
        if (typeof pattern === 'string') {
            // Variable pattern - always matches
            return { matched: true, bindings: { [pattern]: value } };
        }
        
        if (typeof pattern === 'object' && pattern.__patternType) {
            switch (pattern.__patternType) {
                case 'variant':
                    if (value.__type === 'variant' && value.constructor === pattern.constructor) {
                        if (pattern.dataPattern) {
                            const dataResult = FunkRuntime.matchPattern(value.data, pattern.dataPattern);
                            return dataResult;
                        }
                        return { matched: true, bindings: {} };
                    }
                    return { matched: false, bindings: {} };
                    
                case 'record':
                    if (value.__type === 'record') {
                        let bindings = {};
                        for (const [field, fieldPattern] of Object.entries(pattern.fields)) {
                            const fieldResult = FunkRuntime.matchPattern(value[field], fieldPattern);
                            if (!fieldResult.matched) {
                                return { matched: false, bindings: {} };
                            }
                            bindings = { ...bindings, ...fieldResult.bindings };
                        }
                        return { matched: true, bindings };
                    }
                    return { matched: false, bindings: {} };
                    
                case 'literal':
                    return { 
                        matched: value === pattern.value, 
                        bindings: {} 
                    };
                    
                default:
                    return { matched: false, bindings: {} };
            }
        }
        
        // Literal pattern
        return { matched: value === pattern, bindings: {} };
    },
    
    // Function operations
    curry: (fn, arity) => {
        return function curried(...args) {
            if (args.length >= arity) {
                return fn.apply(this, args);
            }
            return (...nextArgs) => curried(...args, ...nextArgs);
        };
    },
    
    // Arithmetic operations
    add: (a, b) => a + b,
    subtract: (a, b) => a - b,
    multiply: (a, b) => a * b,
    divide: (a, b) => a / b,
    
    // String operations
    concat: (a, b) => a + b,
    
    // Comparison operations
    equals: (a, b) => a === b,
    notEquals: (a, b) => a !== b,
    lessThan: (a, b) => a < b,
    lessThanOrEqual: (a, b) => a <= b,
    greaterThan: (a, b) => a > b,
    greaterThanOrEqual: (a, b) => a >= b,
    
    // Boolean operations
    and: (a, b) => a && b,
    or: (a, b) => a || b,
    not: (a) => !a,
    
    // Utility functions
    toString: (value) => {
        if (value && typeof value.toString === 'function') {
            return value.toString();
        }
        return String(value);
    },
    
    // Error handling
    error: (message) => {
        throw new Error(message);
    }
};
        """.trimIndent()
    }
    
    private fun generateItem(item: TypedItem): String {
        return when (item) {
            is TypedLetBinding -> {
                val expr = generateExpression(item.expression)
                "const ${item.variableName} = $expr;"
            }
            is TypedTypeDeclaration -> {
                generateTypeDeclaration(item)
            }
            is TypedEffectDeclaration -> {
                "// Effect: ${item.name}"
            }
            is TypedAbilityDeclaration -> {
                "// Ability: ${item.name}"
            }
            is TypedModuleDeclaration -> {
                "// Module: ${item.name}"
            }
        }
    }
    
    private fun generateTypeDeclaration(typeDecl: TypedTypeDeclaration): String {
        return when (val type = typeDecl.type) {
            is FunkType.VariantType -> {
                val builder = StringBuilder()
                builder.appendLine("// Type: ${typeDecl.name}")
                
                // Generate constructor functions for variants
                for ((constructorName, constructorDataType) in type.constructors) {
                    if (constructorDataType != null) {
                        builder.appendLine("const $constructorName = (data) => FunkRuntime.variant('$constructorName', data);")
                    } else {
                        builder.appendLine("const $constructorName = FunkRuntime.variant('$constructorName');")
                    }
                }
                
                return builder.toString().trimEnd()
            }
            is FunkType.RecordType -> {
                "// Record type: ${typeDecl.name}"
            }
            else -> {
                "// Type alias: ${typeDecl.name}"
            }
        }
    }
    
    private fun generateExpression(typedExpr: TypedExpression): String {
        return when (val expr = typedExpr.expression) {
            is Literal<*> -> generateLiteral(expr)
            
            is Identifier -> {
                // Check if this is a variant constructor (uppercase)
                if (expr.name.first().isUpperCase()) {
                    "FunkRuntime.variant('${expr.name}')"
                } else {
                    expr.name
                }
            }
            
            is LambdaExpression -> generateLambda(expr)
            
            is FunctionCall -> generateFunctionCall(expr)
            
            is RecordLiteral -> generateRecord(expr)
            
            is RecordUpdate -> generateRecordUpdate(expr)
            
            is MemberAccess -> {
                val receiver = generateExpression(TypedExpression(expr.receiver, FunkType.IntType))
                "$receiver.${expr.member}"
            }
            
            is VariantConstruction -> {
                val data = expr.value?.let { generateExpression(TypedExpression(it, FunkType.IntType)) }
                if (data != null) {
                    "FunkRuntime.variant('${expr.constructor}', $data)"
                } else {
                    "FunkRuntime.variant('${expr.constructor}')"
                }
            }
            
            is BinaryOperation -> generateBinaryOperation(expr)
            
            is IfExpression -> generateIfExpression(expr, typedExpr.type)
            
            is MatchExpression -> generateMatchExpression(expr, typedExpr.type)
            
            is DoExpression -> generateDoExpression(expr, typedExpr.type)
            
            is BlockExpression -> generateBlockExpression(expr, typedExpr.type)
            
            is PipeExpression -> generatePipeExpression(expr)
            
            is PlaceholderExpression -> "@"  // Placeholder for pipe operations
            
            else -> "/* Unsupported expression: ${expr::class.simpleName} */"
        }
    }
    
    private fun generateLiteral(literal: Literal<*>): String {
        return when (val value = literal.value) {
            is String -> "\"${value.replace("\"", "\\\"").replace("\n", "\\n")}\""
            is Int, is Float, is Double -> value.toString()
            is Boolean -> value.toString()
            null -> "null"
            else -> value.toString()
        }
    }
    
    private fun generateLambda(lambda: LambdaExpression): String {
        val (params, needsParens) = when (val params = lambda.parameters) {
            is UntypedParameters -> {
                if (params.names.size == 1) {
                    Pair(params.names[0], true)
                } else {
                    Pair("{ ${params.names.joinToString(", ")} }", true)
                }
            }
            is TypedParameters -> {
                // For typed parameters like { x: Int, y: Int }, destructure the record
                val fieldNames = params.recordType.entries.map { it.name }
                Pair("{ ${fieldNames.joinToString(", ")} }", true)
            }
            else -> Pair("param", true)  // Fallback
        }
        
        val body = generateExpression(TypedExpression(lambda.body, FunkType.IntType))
        return if (needsParens) "($params) => $body" else "$params => $body"
    }
    
    private fun generateFunctionCall(call: FunctionCall): String {
        val func = generateExpression(TypedExpression(call.function, FunkType.IntType))
        val arg = generateExpression(TypedExpression(call.arguments, FunkType.IntType))
        return "$func($arg)"
    }
    
    private fun generateRecord(record: RecordLiteral): String {
        val fields = record.entries.joinToString(", ") { entry ->
            val value = entry.value?.let { generateExpression(TypedExpression(it, FunkType.IntType)) } ?: entry.name
            "${entry.name}: $value"
        }
        return "FunkRuntime.record({ $fields })"
    }
    
    private fun generateRecordUpdate(update: RecordUpdate): String {
        val record = generateExpression(TypedExpression(update.record, FunkType.IntType))
        val updates = update.updates.joinToString(", ") { entry ->
            val value = entry.value?.let { generateExpression(TypedExpression(it, FunkType.IntType)) } ?: entry.name
            "${entry.name}: $value"
        }
        return "FunkRuntime.updateRecord($record, { $updates })"
    }
    
    private fun generateBinaryOperation(binOp: BinaryOperation): String {
        val left = generateExpression(TypedExpression(binOp.left, FunkType.IntType))
        val right = generateExpression(TypedExpression(binOp.right, FunkType.IntType))
        
        return when (binOp.operator) {
            "+" -> "FunkRuntime.add($left, $right)"
            "-" -> "FunkRuntime.subtract($left, $right)"
            "*" -> "FunkRuntime.multiply($left, $right)"
            "/" -> "FunkRuntime.divide($left, $right)"
            "++" -> "FunkRuntime.concat($left, $right)"
            "==" -> "FunkRuntime.equals($left, $right)"
            "!=" -> "FunkRuntime.notEquals($left, $right)"
            "<" -> "FunkRuntime.lessThan($left, $right)"
            "<=" -> "FunkRuntime.lessThanOrEqual($left, $right)"
            ">" -> "FunkRuntime.greaterThan($left, $right)"
            ">=" -> "FunkRuntime.greaterThanOrEqual($left, $right)"
            "&&" -> "FunkRuntime.and($left, $right)"
            "||" -> "FunkRuntime.or($left, $right)"
            else -> "($left ${binOp.operator} $right)"
        }
    }
    
    private fun generateIfExpression(ifExpr: IfExpression, resultType: FunkType): String {
        val condition = generateExpression(TypedExpression(ifExpr.condition, FunkType.BooleanType))
        val thenBranch = generateExpression(TypedExpression(ifExpr.thenBranch, resultType))
        val elseBranch = ifExpr.elseBranch?.let { 
            generateExpression(TypedExpression(it, resultType)) 
        } ?: "null"
        return "($condition ? $thenBranch : $elseBranch)"
    }
    
    private fun generateMatchExpression(matchExpr: MatchExpression, resultType: FunkType): String {
        val value = generateExpression(TypedExpression(matchExpr.expression, FunkType.IntType))
        
        val patterns = matchExpr.cases.map { case ->
            val pattern = generatePattern(case.pattern)
            val handler = generatePatternHandler(case.pattern, case.expression, resultType)
            "[$pattern, $handler]"
        }
        
        return "FunkRuntime.match($value, [${patterns.joinToString(", ")}])"
    }
    
    private fun generatePattern(pattern: Pattern): String {
        return when (pattern) {
            is VariablePattern -> "\"${pattern.name}\""  // Variable pattern as string
            is WildcardPattern -> "\"_\""  // Wildcard pattern as string
            is ConstructorPattern -> {
                val recordPattern = pattern.recordPattern
                if (recordPattern != null) {
                    val recordPatternObj = generateRecordPatternObject(recordPattern)
                    "{ __patternType: 'variant', constructor: '${pattern.constructor}', dataPattern: $recordPatternObj }"
                } else {
                    "{ __patternType: 'variant', constructor: '${pattern.constructor}' }"
                }
            }
            is RecordPattern -> {
                val recordPattern = generateRecordPatternObject(pattern)
                "{ __patternType: 'record', fields: $recordPattern }"
            }
            else -> "null"  // Fallback for unsupported patterns
        }
    }
    
    private fun generateRecordPatternObject(recordPattern: RecordPattern): String {
        val fields = recordPattern.entries.map { entry ->
            val fieldPattern = entry.pattern?.let { generatePattern(it) } ?: "\"${entry.name}\""
            "\"${entry.name}\": $fieldPattern"
        }
        return "{ ${fields.joinToString(", ")} }"
    }
    
    private fun generatePatternHandler(pattern: Pattern, expression: Expression, resultType: FunkType): String {
        val bindings = collectPatternBindings(pattern)
        val body = generateExpression(TypedExpression(expression, resultType))
        
        return if (bindings.isNotEmpty()) {
            "(bindings) => { ${bindings.map { "const $it = bindings.$it" }.joinToString("; ")}; return $body; }"
        } else {
            "() => $body"
        }
    }
    
    private fun collectPatternBindings(pattern: Pattern): List<String> {
        return when (pattern) {
            is VariablePattern -> listOf(pattern.name)
            is ConstructorPattern -> {
                val recordPattern = pattern.recordPattern
                if (recordPattern != null) {
                    collectRecordPatternBindings(recordPattern)
                } else {
                    emptyList()
                }
            }
            is RecordPattern -> collectRecordPatternBindings(pattern)
            else -> emptyList()
        }
    }
    
    private fun collectRecordPatternBindings(recordPattern: RecordPattern): List<String> {
        return recordPattern.entries.flatMap { entry ->
            entry.pattern?.let { collectPatternBindings(it) } ?: listOf(entry.name)
        }
    }
    
    private fun generateDoExpression(doExpr: DoExpression, resultType: FunkType): String {
        val statements = doExpr.statements.map { stmt ->
            when (stmt) {
                is LetBinding -> {
                    val expr = generateExpression(TypedExpression(stmt.expression, FunkType.IntType))
                    "const ${stmt.variableName} = $expr"
                }
                is ExpressionStatement -> {
                    generateExpression(TypedExpression(stmt.expression, FunkType.IntType))
                }
                else -> "/* Unsupported statement */"
            }
        }
        
        val result = generateExpression(TypedExpression(doExpr.result, resultType))
        
        return "(() => { ${statements.joinToString("; ")}; return $result; })()"
    }
    
    private fun generateBlockExpression(blockExpr: BlockExpression, resultType: FunkType): String {
        val statements = blockExpr.statements.map { stmt ->
            when (stmt) {
                is LetBinding -> {
                    val expr = generateExpression(TypedExpression(stmt.expression, FunkType.IntType))
                    "const ${stmt.variableName} = $expr"
                }
                is ExpressionStatement -> {
                    generateExpression(TypedExpression(stmt.expression, FunkType.IntType))
                }
                else -> "/* Unsupported statement */"
            }
        }
        
        val result = generateExpression(TypedExpression(blockExpr.result, resultType))
        
        return "(() => { ${statements.joinToString("; ")}; return $result; })()"
    }
    
    private fun generatePipeExpression(pipeExpr: PipeExpression): String {
        val left = generateExpression(TypedExpression(pipeExpr.left, FunkType.IntType))
        val right = generateExpression(TypedExpression(pipeExpr.right, FunkType.IntType))
        
        // Check if right side contains placeholder
        if (right.contains("@")) {
            // Replace placeholder with left value
            return right.replace("@", left)
        } else {
            // Right side is a function - call it with left value
            return "$right($left)"
        }
    }
}
