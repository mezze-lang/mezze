package com.funk.language.cli

import com.funk.language.parser.FunkParserImpl
import com.funk.language.typechecker.TypeChecker
import com.funk.language.typechecker.TypeContext
import com.funk.language.typechecker.TypeEnvironment
import com.funk.language.typechecker.FunkType
import com.funk.language.typechecker.StructuredTypeError
import kotlin.system.exitProcess

/**
 * Native CLI version without interpreter (to avoid Truffle issues)
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: funk-native <command>")
        println("Commands:")
        println("  check    - Type check Funk code from stdin")
        println("  parse    - Parse Funk code from stdin and show AST")
        exitProcess(1)
    }

    val command = args[0]
    val input = generateSequence(::readLine).joinToString("\n")

    try {
        when (command) {
            "check" -> {
                val parser = FunkParserImpl()
                val ast = parser.parse(input)
                
                val typeChecker = TypeChecker()
                val typedProgram = typeChecker.typeCheck(ast)
                
                println("✅ Type checking successful!")
                println()
                println("Variables:")
                
                // Print variable types from typed program
                for (item in typedProgram.items) {
                    when (item) {
                        is com.funk.language.typechecker.TypedLetBinding -> {
                            val prettyType = prettyPrintType(item.inferredType)
                            println("  ${item.variableName} : $prettyType")
                        }
                        else -> {
                            // Skip other items for now
                        }
                    }
                }
            }
            
            "parse" -> {
                val parser = FunkParserImpl()
                val ast = parser.parse(input)
                
                println("✅ Parsing successful!")
                println()
                println("AST:")
                println(ast.toString())
            }
            
            "exec" -> {
                println("❌ Execution not available in native build")
                println("Use the JAR version for execution: java -jar funk.jar exec")
                exitProcess(1)
            }
            
            else -> {
                println("❌ Unknown command: $command")
                println("Available commands: check, parse")
                exitProcess(1)
            }
        }
    } catch (e: StructuredTypeError) {
        println("❌ ${e.message}")
        exitProcess(1)
    } catch (e: Exception) {
        println("❌ Error: ${e.message}")
        exitProcess(1)
    }
}

/**
 * Pretty print types for display
 */
private fun prettyPrintType(type: FunkType): String {
    return when (type) {
        is FunkType.IntType -> "Int"
        is FunkType.FloatType -> "Float"
        is FunkType.StringType -> "String"
        is FunkType.BooleanType -> "Boolean"
        is FunkType.VarType -> type.variable.name
        is FunkType.FunctionType -> {
            val paramType = prettyPrintType(type.paramType)
            val returnType = prettyPrintType(type.returnType)
            "$paramType -> $returnType"
        }
        is FunkType.RecordType -> {
            val fields = type.fields.entries.joinToString(", ") { (name, fieldType) ->
                "$name: ${prettyPrintType(fieldType)}"
            }
            val openMarker = if (type.rowVar != null) ", ..." else ""
            "{ $fields$openMarker }"
        }
        is FunkType.VariantType -> {
            val constructors = type.constructors.entries.joinToString(", ") { (name, dataType) ->
                when (dataType) {
                    null -> name
                    is FunkType.RecordType -> {
                        val fields = dataType.fields.entries.joinToString(", ") { (fieldName, fieldType) ->
                            "$fieldName: ${prettyPrintType(fieldType)}"
                        }
                        val openMarker = if (dataType.rowVar != null) ", ..." else ""
                        "$name { $fields$openMarker }"
                    }
                    else -> "$name(${prettyPrintType(dataType)})"
                }
            }
            val openMarker = if (type.rowVar != null) ", ..." else ""
            "| $constructors$openMarker |"
        }
        else -> type.toString()
    }
}
