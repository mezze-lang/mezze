package com.funk.language.compiler

import com.funk.language.typechecker.*
import com.funk.language.parser.*

/**
 * LLVM IR code generator for Funk language
 * Generates native-compilable LLVM IR that can be compiled to machine code
 */
class LLVMCodeGen {
    
    private var nextTempId = 0
    private var nextLabelId = 0
    private val stringLiterals = mutableMapOf<String, String>()
    
    fun generateProgram(program: TypedProgram): String {
        val builder = StringBuilder()
        
        // LLVM module header
        builder.appendLine("; Funk Language - LLVM IR Generated Code")
        builder.appendLine("; Target: Native Binary via LLVM")
        builder.appendLine()
        
        // Target and data layout
        builder.appendLine("target triple = \"${getTargetTriple()}\"")
        builder.appendLine("target datalayout = \"${getDataLayout()}\"")
        builder.appendLine()
        
        // Runtime declarations
        builder.appendLine(generateRuntimeDeclarations())
        builder.appendLine()
        
        // String literals
        generateStringLiterals(program, builder)
        
        // Global variables for let bindings
        generateGlobalDeclarations(program, builder)
        
        // Function definitions
        generateFunctions(program, builder)
        
        // Main function
        generateMainFunction(program, builder)
        
        return builder.toString()
    }
    
    private fun getTargetTriple(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        
        return when {
            os.contains("mac") && arch.contains("aarch64") -> "arm64-apple-macosx"
            os.contains("mac") -> "x86_64-apple-macosx"
            os.contains("linux") && arch.contains("aarch64") -> "aarch64-unknown-linux-gnu"
            os.contains("linux") -> "x86_64-unknown-linux-gnu"
            os.contains("windows") -> "x86_64-pc-windows-msvc"
            else -> "x86_64-unknown-unknown"
        }
    }
    
    private fun getDataLayout(): String {
        val triple = getTargetTriple()
        return when {
            triple.startsWith("arm64-apple") -> "e-m:o-i64:64-i128:128-n32:64-S128"
            triple.startsWith("x86_64-apple") -> "e-m:o-i64:64-f80:128-n8:16:32:64-S128"
            triple.startsWith("x86_64-unknown-linux") -> "e-m:e-p270:32:32-p271:32:32-p272:64:64-i64:64-f80:128-n8:16:32:64-S128"
            else -> "e-m:e-i64:64-f80:128-n8:16:32:64-S128"
        }
    }
    
    private fun generateRuntimeDeclarations(): String {
        return """
; Runtime function declarations
declare i32 @printf(i8*, ...)
declare i8* @malloc(i64)
declare void @free(i8*)
declare i32 @strcmp(i8*, i8*)
declare i8* @strcpy(i8*, i8*)
declare i64 @strlen(i8*)

; Funk runtime functions
declare %funk_value* @funk_create_int(i64)
declare %funk_value* @funk_create_float(double)
declare %funk_value* @funk_create_string(i8*)
declare %funk_value* @funk_create_variant(i8*, %funk_value*)
declare %funk_value* @funk_create_record()
declare void @funk_record_set(i8*, %funk_value*, %funk_value*)
declare %funk_value* @funk_record_get(i8*, %funk_value*)
declare i64 @funk_get_int(%funk_value*)
declare double @funk_get_float(%funk_value*)
declare i8* @funk_get_string(%funk_value*)
declare i32 @funk_match_variant(i8*, %funk_value*)

; Funk value type (boxed values for dynamic typing)
%funk_value = type { i32, i8* }
        """.trimIndent()
    }
    
    private fun generateStringLiterals(program: TypedProgram, builder: StringBuilder) {
        // Collect all string literals
        collectStringLiterals(program)
        
        if (stringLiterals.isNotEmpty()) {
            builder.appendLine("; String literals")
            for ((literal, name) in stringLiterals) {
                val escaped = literal.replace("\\", "\\\\").replace("\"", "\\\"")
                val length = literal.length + 1 // +1 for null terminator
                builder.appendLine("$name = private unnamed_addr constant [$length x i8] c\"$escaped\\00\"")
            }
            builder.appendLine()
        }
    }
    
    private fun collectStringLiterals(program: TypedProgram) {
        for (item in program.items) {
            if (item is TypedLetBinding) {
                collectStringLiteralsFromExpression(item.expression)
            }
        }
    }
    
    private fun collectStringLiteralsFromExpression(expr: TypedExpression) {
        when (val e = expr.expression) {
            is Literal<*> -> {
                if (e.value is String) {
                    val literal = e.value as String
                    if (!stringLiterals.containsKey(literal)) {
                        stringLiterals[literal] = "@str_${stringLiterals.size}"
                    }
                }
            }
            is LambdaExpression -> collectStringLiteralsFromExpression(TypedExpression(e.body, expr.type))
            is FunctionCall -> {
                collectStringLiteralsFromExpression(TypedExpression(e.function, expr.type))
                collectStringLiteralsFromExpression(TypedExpression(e.arguments, expr.type))
            }
            is BinaryOperation -> {
                collectStringLiteralsFromExpression(TypedExpression(e.left, expr.type))
                collectStringLiteralsFromExpression(TypedExpression(e.right, expr.type))
            }
            is MatchExpression -> {
                collectStringLiteralsFromExpression(TypedExpression(e.expression, expr.type))
                for (case in e.cases) {
                    collectStringLiteralsFromExpression(TypedExpression(case.expression, expr.type))
                }
            }
            is IfExpression -> {
                collectStringLiteralsFromExpression(TypedExpression(e.condition, expr.type))
                collectStringLiteralsFromExpression(TypedExpression(e.thenBranch, expr.type))
                collectStringLiteralsFromExpression(TypedExpression(e.elseBranch, expr.type))
            }
            is VariantConstruction -> {
                e.value?.let { collectStringLiteralsFromExpression(TypedExpression(it, expr.type)) }
            }
            is RecordLiteral -> {
                for (entry in e.entries) {
                    entry.value?.let { collectStringLiteralsFromExpression(TypedExpression(it, expr.type)) }
                }
            }
            is MemberAccess -> collectStringLiteralsFromExpression(TypedExpression(e.receiver, expr.type))
            is RecordUpdate -> {
                collectStringLiteralsFromExpression(TypedExpression(e.record, expr.type))
                for (update in e.updates) {
                    update.value?.let { collectStringLiteralsFromExpression(TypedExpression(it, expr.type)) }
                }
            }
            is BlockExpression -> {
                for (stmt in e.statements) {
                    if (stmt is ExpressionStatement) {
                        collectStringLiteralsFromExpression(TypedExpression(stmt.expression, expr.type))
                    }
                }
                collectStringLiteralsFromExpression(TypedExpression(e.result, expr.type))
            }
            is DoExpression -> {
                for (stmt in e.statements) {
                    if (stmt is ExpressionStatement) {
                        collectStringLiteralsFromExpression(TypedExpression(stmt.expression, expr.type))
                    }
                }
                collectStringLiteralsFromExpression(TypedExpression(e.result, expr.type))
            }
            is HandleExpression -> collectStringLiteralsFromExpression(TypedExpression(e.expression, expr.type))
            is PipeExpression -> {
                collectStringLiteralsFromExpression(TypedExpression(e.left, expr.type))
                collectStringLiteralsFromExpression(TypedExpression(e.right, expr.type))
            }
            is Identifier -> { /* No string literals in identifiers */ }
            is PlaceholderExpression -> { /* No string literals in placeholders */ }
        }
    }
    
    private fun generateGlobalDeclarations(program: TypedProgram, builder: StringBuilder) {
        builder.appendLine("; Global variable declarations")
        for (item in program.items) {
            if (item is TypedLetBinding) {
                builder.appendLine("@${item.variableName} = global %funk_value* null")
            }
        }
        builder.appendLine()
    }
    
    private fun generateFunctions(program: TypedProgram, builder: StringBuilder) {
        // Generate lambda functions as separate LLVM functions
        for (item in program.items) {
            if (item is TypedLetBinding) {
                val expr = item.expression.expression
                if (expr is LambdaExpression) {
                    generateLambdaFunction(item.variableName, expr, item.expression.type, builder)
                }
            }
        }
    }
    
    private fun generateLambdaFunction(name: String, lambda: LambdaExpression, type: FunkType, builder: StringBuilder) {
        builder.appendLine("; Function: $name")
        
        // Generate function signature
        val params = when (val paramType = lambda.parameters) {
            is UntypedParameters -> paramType.names.joinToString(", ") { "%funk_value*" }
            is TypedParameters -> paramType.recordType.entries.joinToString(", ") { "%funk_value*" }
        }
        
        builder.appendLine("define %funk_value* @func_$name($params) {")
        builder.appendLine("entry:")
        
        // Generate function body
        val bodyCode = generateExpression(lambda.body, type)
        builder.appendLine("  $bodyCode")
        builder.appendLine("  ret %funk_value* %${nextTempId - 1}")
        
        builder.appendLine("}")
        builder.appendLine()
    }
    
    private fun generateMainFunction(program: TypedProgram, builder: StringBuilder) {
        builder.appendLine("; Main function")
        builder.appendLine("define i32 @main() {")
        builder.appendLine("entry:")
        
        // Initialize global variables
        for (item in program.items) {
            if (item is TypedLetBinding) {
                val expr = item.expression.expression
                if (expr !is LambdaExpression) {
                    // Generate initialization code
                    val valueCode = generateExpression(item.expression.expression, item.expression.type)
                    builder.appendLine("  $valueCode")
                    builder.appendLine("  store %funk_value* %${nextTempId - 1}, %funk_value** @${item.variableName}")
                }
            }
        }
        
        // Print results (for demonstration)
        builder.appendLine("  ; Print program results")
        for (item in program.items) {
            if (item is TypedLetBinding) {
                builder.appendLine("  %load_${item.variableName} = load %funk_value*, %funk_value** @${item.variableName}")
                builder.appendLine("  call void @print_funk_value(%funk_value* %load_${item.variableName})")
            }
        }
        
        builder.appendLine("  ret i32 0")
        builder.appendLine("}")
        builder.appendLine()
        
        // Helper function to print funk values
        generatePrintFunction(builder)
    }
    
    private fun generateExpression(expr: Expression, type: FunkType): String {
        return when (expr) {
            is Literal<*> -> generateLiteral(expr)
            is Identifier -> generateIdentifier(expr)
            is BinaryOperation -> generateBinaryOperation(expr, type)
            is MatchExpression -> generateMatchExpression(expr, type)
            is VariantConstruction -> generateVariantConstruction(expr)
            is RecordLiteral -> generateRecordLiteral(expr)
            is FunctionCall -> generateFunctionCall(expr, type)
            is IfExpression -> generateIfExpression(expr, type)
            is MemberAccess -> generateMemberAccess(expr, type)
            is PipeExpression -> generatePipeExpression(expr, type)
            is BlockExpression -> generateBlockExpression(expr, type)
            is DoExpression -> generateDoExpression(expr, type)
            is HandleExpression -> generateHandleExpression(expr, type)
            is RecordUpdate -> generateRecordUpdate(expr, type)
            is PlaceholderExpression -> generatePlaceholder()
            else -> {
                val temp = nextTemp()
                "  ; Unsupported expression: ${expr::class.simpleName}\n  $temp = call %funk_value* @funk_create_int(i64 0)"
            }
        }
    }
    
    private fun generateLiteral(literal: Literal<*>): String {
        val temp = nextTemp()
        return when (val value = literal.value) {
            is Int -> "  $temp = call %funk_value* @funk_create_int(i64 ${value.toLong()})"
            is Long -> "  $temp = call %funk_value* @funk_create_int(i64 $value)"
            is Float -> "  $temp = call %funk_value* @funk_create_float(double ${value.toDouble()})"
            is Double -> "  $temp = call %funk_value* @funk_create_float(double $value)"
            is String -> {
                val strName = stringLiterals[value] ?: "@str_unknown"
                "  $temp = call %funk_value* @funk_create_string(i8* getelementptr inbounds ([${value.length + 1} x i8], [${value.length + 1} x i8]* $strName, i32 0, i32 0))"
            }
            is Boolean -> "  $temp = call %funk_value* @funk_create_int(i64 ${if (value) 1 else 0})"
            else -> "  $temp = call %funk_value* @funk_create_int(i64 0) ; Unknown literal type"
        }
    }
    
    private fun generateIdentifier(id: Identifier): String {
        val temp = nextTemp()
        return if (id.name.first().isUpperCase()) {
            // Variant constructor
            "  $temp = call %funk_value* @funk_create_variant(i8* getelementptr inbounds ([${id.name.length + 1} x i8], [${id.name.length + 1} x i8]* @variant_${id.name}, i32 0, i32 0), %funk_value* null)"
        } else {
            // Variable reference
            "  $temp = load %funk_value*, %funk_value** @${id.name}"
        }
    }
    
    private fun generateBinaryOperation(binOp: BinaryOperation, type: FunkType): String {
        val leftCode = generateExpression(binOp.left, type)
        val leftTemp = nextTempId - 1
        
        val rightCode = generateExpression(binOp.right, type)
        val rightTemp = nextTempId - 1
        
        val resultTemp = nextTemp()
        
        val operation = when (binOp.operator) {
            "+" -> "add"
            "-" -> "sub"
            "*" -> "mul"
            "/" -> "sdiv"
            else -> "add" // Default fallback
        }
        
        return """$leftCode
  $rightCode
  %left_val_$resultTemp = call i64 @funk_get_int(%funk_value* %$leftTemp)
  %right_val_$resultTemp = call i64 @funk_get_int(%funk_value* %$rightTemp)
  %result_$resultTemp = $operation i64 %left_val_$resultTemp, %right_val_$resultTemp
  $resultTemp = call %funk_value* @funk_create_int(i64 %result_$resultTemp)"""
    }
    
    private fun generateMatchExpression(match: MatchExpression, type: FunkType): String {
        val exprCode = generateExpression(match.expression, type)
        val exprTemp = nextTempId - 1
        
        val builder = StringBuilder()
        builder.appendLine(exprCode)
        
        val endLabel = nextLabel("match_end")
        val resultTemp = nextTemp()
        
        // Allocate result variable
        builder.appendLine("  %result_ptr_$resultTemp = alloca %funk_value*")
        
        for ((index, case) in match.cases.withIndex()) {
            val caseLabel = nextLabel("match_case_$index")
            val nextLabel = if (index < match.cases.size - 1) nextLabel("match_case_${index + 1}") else endLabel
            
            when (val pattern = case.pattern) {
                is ConstructorPattern -> {
                    builder.appendLine("  %match_$index = call i32 @funk_match_variant(i8* getelementptr inbounds ([${pattern.constructor.length + 1} x i8], [${pattern.constructor.length + 1} x i8]* @variant_${pattern.constructor}, i32 0, i32 0), %funk_value* %$exprTemp)")
                    builder.appendLine("  %cmp_$index = icmp eq i32 %match_$index, 1")
                    builder.appendLine("  br i1 %cmp_$index, label %$caseLabel, label %$nextLabel")
                    builder.appendLine()
                    builder.appendLine("$caseLabel:")
                    val caseCode = generateExpression(case.expression, type)
                    builder.appendLine(caseCode)
                    builder.appendLine("  store %funk_value* %${nextTempId - 1}, %funk_value** %result_ptr_$resultTemp")
                    builder.appendLine("  br label %$endLabel")
                    builder.appendLine()
                }
                else -> {
                    // Wildcard or other patterns - always match
                    builder.appendLine("  br label %$caseLabel")
                    builder.appendLine()
                    builder.appendLine("$caseLabel:")
                    val caseCode = generateExpression(case.expression, type)
                    builder.appendLine(caseCode)
                    builder.appendLine("  store %funk_value* %${nextTempId - 1}, %funk_value** %result_ptr_$resultTemp")
                    builder.appendLine("  br label %$endLabel")
                    builder.appendLine()
                }
            }
        }
        
        builder.appendLine("$endLabel:")
        builder.appendLine("  $resultTemp = load %funk_value*, %funk_value** %result_ptr_$resultTemp")
        
        return builder.toString().trimEnd()
    }
    
    private fun generateVariantConstruction(variant: VariantConstruction): String {
        val temp = nextTemp()
        val value = variant.value
        return if (value != null) {
            val code = generateExpression(value, FunkType.IntType) // Placeholder type
            val valueTemp = nextTempId - 1
            "$code\n  $temp = call %funk_value* @funk_create_variant(i8* getelementptr inbounds ([${variant.constructor.length + 1} x i8], [${variant.constructor.length + 1} x i8]* @variant_${variant.constructor}, i32 0, i32 0), %funk_value* %$valueTemp)"
        } else {
            "  $temp = call %funk_value* @funk_create_variant(i8* getelementptr inbounds ([${variant.constructor.length + 1} x i8], [${variant.constructor.length + 1} x i8]* @variant_${variant.constructor}, i32 0, i32 0), %funk_value* null)"
        }
    }
    
    private fun generateRecordLiteral(record: RecordLiteral): String {
        val temp = nextTemp()
        val builder = StringBuilder()
        
        builder.appendLine("  $temp = call %funk_value* @funk_create_record()")
        
        for (entry in record.entries) {
            val value = entry.value
            if (value != null) {
                val valueCode = generateExpression(value, FunkType.IntType) // Placeholder type
                val valueTemp = nextTempId - 1
                builder.appendLine(valueCode)
                builder.appendLine("  call void @funk_record_set(i8* getelementptr inbounds ([${entry.name.length + 1} x i8], [${entry.name.length + 1} x i8]* @field_${entry.name}, i32 0, i32 0), %funk_value* $temp, %funk_value* %$valueTemp)")
            }
        }
        
        return builder.toString().trimEnd()
    }
    
    private fun generateFunctionCall(call: FunctionCall, type: FunkType): String {
        val temp = nextTemp()
        val funcCode = generateExpression(call.function, type)
        val argsCode = generateExpression(call.arguments, type)
        
        return """$funcCode
  $argsCode
  $temp = call %funk_value* @func_${(call.function as? Identifier)?.name ?: "unknown"}(%funk_value* %${nextTempId - 1})"""
    }
    
    private fun generatePrintFunction(builder: StringBuilder) {
        builder.appendLine("; Helper function to print funk values")
        builder.appendLine("define void @print_funk_value(%funk_value* %value) {")
        builder.appendLine("entry:")
        builder.appendLine("  %int_val = call i64 @funk_get_int(%funk_value* %value)")
        builder.appendLine("  %fmt = getelementptr inbounds [4 x i8], [4 x i8]* @print_fmt, i32 0, i32 0")
        builder.appendLine("  call i32 (i8*, ...) @printf(i8* %fmt, i64 %int_val)")
        builder.appendLine("  ret void")
        builder.appendLine("}")
        builder.appendLine()
        builder.appendLine("@print_fmt = private unnamed_addr constant [4 x i8] c\"%ld\\0A\\00\"")
    }
    
    private fun nextTemp(): String {
        return "%temp_${nextTempId++}"
    }
    
    private fun nextLabel(prefix: String): String {
        return "${prefix}_${nextLabelId++}"
    }
    
    // Additional expression generators for completeness
    private fun generateIfExpression(ifExpr: IfExpression, type: FunkType): String {
        val temp = nextTemp()
        val condCode = generateExpression(ifExpr.condition, type)
        val condTemp = nextTempId - 1
        
        val thenLabel = nextLabel("if_then")
        val elseLabel = nextLabel("if_else")
        val endLabel = nextLabel("if_end")
        
        val builder = StringBuilder()
        builder.appendLine(condCode)
        builder.appendLine("  %cond_val = call i64 @funk_get_int(%funk_value* %$condTemp)")
        builder.appendLine("  %cond_bool = icmp ne i64 %cond_val, 0")
        builder.appendLine("  br i1 %cond_bool, label %$thenLabel, label %$elseLabel")
        builder.appendLine()
        builder.appendLine("$thenLabel:")
        val thenCode = generateExpression(ifExpr.thenBranch, type)
        val thenTemp = nextTempId - 1
        builder.appendLine(thenCode)
        builder.appendLine("  br label %$endLabel")
        builder.appendLine()
        builder.appendLine("$elseLabel:")
        val elseCode = generateExpression(ifExpr.elseBranch, type)
        val elseTemp = nextTempId - 1
        builder.appendLine(elseCode)
        builder.appendLine("  br label %$endLabel")
        builder.appendLine()
        builder.appendLine("$endLabel:")
        builder.appendLine("  $temp = phi %funk_value* [%$thenTemp, %$thenLabel], [%$elseTemp, %$elseLabel]")
        
        return builder.toString().trimEnd()
    }
    
    private fun generateMemberAccess(member: MemberAccess, type: FunkType): String {
        val temp = nextTemp()
        val receiverCode = generateExpression(member.receiver, type)
        val receiverTemp = nextTempId - 1
        
        return """$receiverCode
  $temp = call %funk_value* @funk_record_get(i8* getelementptr inbounds ([${member.member.length + 1} x i8], [${member.member.length + 1} x i8]* @field_${member.member}, i32 0, i32 0), %funk_value* %$receiverTemp)"""
    }
    
    private fun generatePipeExpression(pipe: PipeExpression, type: FunkType): String {
        // For now, just chain the expressions
        val leftCode = generateExpression(pipe.left, type)
        val rightCode = generateExpression(pipe.right, type)
        return "$leftCode\n$rightCode"
    }
    
    private fun generateBlockExpression(block: BlockExpression, type: FunkType): String {
        val builder = StringBuilder()
        
        // Generate statements
        for (stmt in block.statements) {
            when (stmt) {
                is ExpressionStatement -> {
                    val stmtCode = generateExpression(stmt.expression, type)
                    builder.appendLine(stmtCode)
                }
                is LetBinding -> {
                    // Handle let bindings in blocks
                    val valueCode = generateExpression(stmt.expression, type)
                    builder.appendLine(valueCode)
                }
                is ResumeStatement -> {
                    val resumeCode = generateExpression(stmt.expression, type)
                    builder.appendLine(resumeCode)
                }
                else -> {
                    // Other statement types - placeholder
                    builder.appendLine("  ; Unsupported statement: ${stmt::class.simpleName}")
                }
            }
        }
        
        // Generate result expression
        val resultCode = generateExpression(block.result, type)
        builder.appendLine(resultCode)
        
        return builder.toString().trimEnd()
    }
    
    private fun generateDoExpression(doExpr: DoExpression, type: FunkType): String {
        // Similar to block expression
        return generateBlockExpression(BlockExpression(doExpr.statements, doExpr.result), type)
    }
    
    private fun generateHandleExpression(handle: HandleExpression, type: FunkType): String {
        // Simplified handle expression - just evaluate the main expression
        return generateExpression(handle.expression, type)
    }
    
    private fun generateRecordUpdate(update: RecordUpdate, type: FunkType): String {
        val temp = nextTemp()
        val recordCode = generateExpression(update.record, type)
        val recordTemp = nextTempId - 1
        
        val builder = StringBuilder()
        builder.appendLine(recordCode)
        builder.appendLine("  $temp = call %funk_value* @funk_create_record() ; Copy record for update")
        
        // Apply updates
        for (entry in update.updates) {
            val value = entry.value
            if (value != null) {
                val valueCode = generateExpression(value, type)
                val valueTemp = nextTempId - 1
                builder.appendLine(valueCode)
                builder.appendLine("  call void @funk_record_set(i8* getelementptr inbounds ([${entry.name.length + 1} x i8], [${entry.name.length + 1} x i8]* @field_${entry.name}, i32 0, i32 0), %funk_value* $temp, %funk_value* %$valueTemp)")
            }
        }
        
        return builder.toString().trimEnd()
    }
    
    private fun generatePlaceholder(): String {
        val temp = nextTemp()
        return "  $temp = call %funk_value* @funk_create_int(i64 0) ; Placeholder"
    }
}
