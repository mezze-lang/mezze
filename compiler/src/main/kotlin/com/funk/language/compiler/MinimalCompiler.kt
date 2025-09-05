package com.funk.language.compiler

import com.funk.language.typechecker.TypedProgram as TypecheckerTypedProgram
import com.funk.language.typechecker.TypedItem
import com.funk.language.typechecker.TypedLetBinding
import com.funk.language.typechecker.TypedExpression
import com.funk.language.typechecker.FunkType
import com.funk.language.parser.*

/**
 * Minimal working compiler for Funk language
 */
class MinimalCompiler {
    
    fun compile(
        typedProgram: TypecheckerTypedProgram,
        target: CompilationTarget = CompilationTarget.JavaScript
    ): CompilationResult {
        return try {
            val code = when (target) {
                CompilationTarget.JavaScript -> generateJavaScript(typedProgram)
                CompilationTarget.JVM -> generateJVM(typedProgram)
                CompilationTarget.LLVM -> generateLLVM(typedProgram)
                CompilationTarget.C -> generateC(typedProgram)
            }
            
            CompilationResult.Success(
                target = target,
                code = code,
                metadata = CompilationMetadata(
                    sourceItems = typedProgram.items.size,
                    generatedLines = code.split('\n').size,
                    optimizationLevel = OptimizationLevel.O1
                )
            )
        } catch (e: Exception) {
            CompilationResult.Error(
                message = "Compilation failed: ${e.message}",
                cause = e
            )
        }
    }
    
    private fun generateJavaScript(program: TypecheckerTypedProgram): String {
        val builder = StringBuilder()
        
        // Add runtime
        builder.appendLine("""
            // Funk Runtime Library
            const FunkRuntime = {
                variant: (constructor, data = null) => ({ __type: 'variant', constructor, data }),
                record: (fields) => ({ __type: 'record', ...fields }),
                updateRecord: (record, updates) => ({ ...record, ...updates })
            };
        """.trimIndent())
        
        builder.appendLine()
        
        // Generate code for each item
        for (item in program.items) {
            when (item) {
                is TypedLetBinding -> {
                    val expr = generateJSExpression(item.expression)
                    builder.appendLine("const ${item.variableName} = $expr;")
                }
                else -> {
                    builder.appendLine("// Unsupported item: ${item::class.simpleName}")
                }
            }
        }
        
        return builder.toString()
    }
    
    private fun generateJSExpression(typedExpr: TypedExpression): String {
        return when (val expr = typedExpr.expression) {
            is Literal<*> -> generateJSLiteral(expr)
            is Identifier -> {
                // Check if this is a variant constructor (uppercase)
                if (expr.name.first().isUpperCase()) {
                    "FunkRuntime.variant('${expr.name}')"
                } else {
                    expr.name
                }
            }
            is LambdaExpression -> generateJSLambda(expr)
            is FunctionCall -> generateJSFunctionCall(expr)
            is RecordLiteral -> generateJSRecord(expr)
            is MemberAccess -> "${generateJSExpression(TypedExpression(expr.receiver, FunkType.IntType))}.${expr.member}"
            is VariantConstruction -> {
                val data = expr.value?.let { generateJSExpression(TypedExpression(it, FunkType.IntType)) }
                "FunkRuntime.variant('${expr.constructor}'${data?.let { ", $it" } ?: ""})"
            }
            is BinaryOperation -> {
                val left = generateJSExpression(TypedExpression(expr.left, FunkType.IntType))
                val right = generateJSExpression(TypedExpression(expr.right, FunkType.IntType))
                val op = when (expr.operator) {
                    "++" -> "+"
                    else -> expr.operator
                }
                "($left $op $right)"
            }
            is IfExpression -> {
                val condition = generateJSExpression(TypedExpression(expr.condition, FunkType.BooleanType))
                val thenBranch = generateJSExpression(TypedExpression(expr.thenBranch, typedExpr.type))
                val elseBranch = expr.elseBranch?.let { generateJSExpression(TypedExpression(it, typedExpr.type)) } ?: "null"
                "($condition ? $thenBranch : $elseBranch)"
            }
            else -> "/* Unsupported expression: ${expr::class.simpleName} */"
        }
    }
    
    private fun generateJSLiteral(literal: Literal<*>): String {
        return when (val value = literal.value) {
            is String -> "\"${value.replace("\"", "\\\"")}\""
            is Int, is Float, is Double -> value.toString()
            is Boolean -> value.toString()
            null -> "null"
            else -> value.toString()
        }
    }
    
    private fun generateJSLambda(lambda: LambdaExpression): String {
        val params = when (val params = lambda.parameters) {
            is UntypedParameters -> {
                if (params.names.size == 1) {
                    params.names[0]
                } else {
                    "({ ${params.names.joinToString(", ")} })"
                }
            }
            else -> "param"
        }
        
        val body = generateJSExpression(TypedExpression(lambda.body, FunkType.IntType))
        return "($params) => $body"
    }
    
    private fun generateJSFunctionCall(call: FunctionCall): String {
        val func = generateJSExpression(TypedExpression(call.function, FunkType.IntType))
        val arg = generateJSExpression(TypedExpression(call.arguments, FunkType.IntType))
        return "$func($arg)"
    }
    
    private fun generateJSRecord(record: RecordLiteral): String {
        val fields = record.entries.joinToString(", ") { entry ->
            val value = entry.value?.let { generateJSExpression(TypedExpression(it, FunkType.IntType)) } ?: entry.name
            "${entry.name}: $value"
        }
        return "FunkRuntime.record({ $fields })"
    }
    
    private fun generateJVM(program: TypecheckerTypedProgram): String {
        return "// JVM compilation not implemented yet"
    }
    
    private fun generateLLVM(program: TypecheckerTypedProgram): String {
        return "; LLVM compilation not implemented yet"
    }
    
    private fun generateC(program: TypecheckerTypedProgram): String {
        return "// C compilation not implemented yet"
    }
}
