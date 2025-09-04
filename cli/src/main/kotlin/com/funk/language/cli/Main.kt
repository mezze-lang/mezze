package com.funk.language.cli

import com.funk.language.parser.FunkParserImpl
import com.funk.language.typechecker.*
import java.io.File

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        return
    }

    when (args[0]) {
        "parse" -> handleParseCommand(args.drop(1))
        "check" -> handleCheckCommand(args.drop(1))
        else -> {
            println("Error: Unknown command '${args[0]}'")
            printUsage()
        }
    }
}

private fun printUsage() {
    println("Usage:")
    println("  funk parse [file]    - Parse a file (or stdin if no file) and pretty-print the AST")
    println("  funk check [file]    - Type check a file (or stdin if no file) and pretty-print the types")
}

private fun handleParseCommand(args: List<String>) {
    val source = when {
        args.isEmpty() -> {
            // Read from stdin
            readStdin()
        }
        args.size == 1 -> {
            // Read from file
            val file = File(args[0])
            if (!file.exists()) {
                println("Error: File not found at '${args[0]}'")
                return
            }
            file.readText()
        }
        else -> {
            println("Error: parse command takes at most 1 argument (filename)")
            printUsage()
            return
        }
    }

    try {
        val parser = FunkParserImpl()
        val ast = parser.parse(source)
        println("✅ Parsing successful!")
        println()
        println(prettyPrintAST(ast))
    } catch (e: Exception) {
        println("❌ Parse error:")
        println(e.message ?: "Unknown error")
    }
}

private fun handleCheckCommand(args: List<String>) {
    val source = when {
        args.isEmpty() -> {
            // Read from stdin
            readStdin()
        }
        args.size == 1 -> {
            // Read from file
            val file = File(args[0])
            if (!file.exists()) {
                println("Error: File not found at '${args[0]}'")
                return
            }
            file.readText()
        }
        else -> {
            println("Error: check command takes at most 1 argument (filename)")
            printUsage()
            return
        }
    }

    try {
        val parser = FunkParserImpl()
        val ast = parser.parse(source)
        
        val typeChecker = TypeChecker()
        val result = typeChecker.typeCheck(ast)
        
        println("✅ Type checking successful!")
        println()
        println("Variables:")
        for (item in result.items) {
            when (item) {
                is TypedLetBinding -> {
                    println("  ${item.variableName} : ${prettyPrintType(item.inferredType)}")
                }
                is TypedTypeDeclaration -> {
                    println("  type ${item.name}")
                }
                else -> {
                    // Skip other items for now
                }
            }
        }
    } catch (e: StructuredTypeError) {
        // For demonstration, show both simple and detailed error formats
        println(ErrorPrettyPrinter.prettyPrintErrorSimple(e))
        
        // Uncomment the line below to see the detailed error format:
        // println("\n" + ErrorPrettyPrinter.prettyPrintError(e))
        
        // Exit with error code for type errors
        kotlin.system.exitProcess(1)
    } catch (e: Exception) {
        println("❌ Error:")
        println(e.message ?: "Unknown error")
        kotlin.system.exitProcess(1)
    }
}

private fun readStdin(): String {
    val lines = mutableListOf<String>()
    var line = readLine()
    while (line != null) {
        lines.add(line)
        line = readLine()
    }
    return lines.joinToString("\n")
}

private fun prettyPrintType(type: FunkType): String = when (type) {
    is FunkType.IntType -> "Int"
    is FunkType.FloatType -> "Float"
    is FunkType.StringType -> "String"
    is FunkType.BooleanType -> "Boolean"
    is FunkType.VarType -> type.variable.name
    is FunkType.RecordType -> {
        val fields = type.fields.entries.joinToString(", ") { (name, fieldType) ->
            "$name: ${prettyPrintType(fieldType)}"
        }
        val openMarker = if (type.rowVar != null) ", ..." else ""
        "{ $fields$openMarker }"
    }
    is FunkType.VariantType -> {
        val constructors = type.constructors.entries.joinToString(", ") { (name, dataType) ->
            if (dataType != null) "$name(${prettyPrintType(dataType)})" else name
        }
        val openMarker = if (type.rowVar != null) ", ..." else ""
        "| $constructors$openMarker |"
    }
    is FunkType.FunctionType -> {
        "${prettyPrintType(type.paramType)} -> ${prettyPrintType(type.returnType)}"
    }
    is FunkType.TypeAlias -> type.name
    is FunkType.TypeConstructor -> {
        if (type.args.isEmpty()) type.name
        else "${type.name}<${type.args.joinToString(", ") { prettyPrintType(it) }}>"
    }
}


private fun prettyPrintAST(ast: com.funk.language.parser.Program): String {
    val sb = StringBuilder()
    sb.appendLine("Program with ${ast.items.size} items:")
    ast.items.forEachIndexed { index, item ->
        sb.appendLine("  ${index + 1}. ${prettyPrintItem(item)}")
    }
    return sb.toString()
}

private fun prettyPrintItem(item: com.funk.language.parser.ProgramItem): String {
    return when (item) {
        is com.funk.language.parser.LetBinding -> {
            val typeAnnotation = item.typeAnnotation?.let { ": $it" } ?: ""
            "let ${item.variableName}$typeAnnotation = ${prettyPrintExpression(item.expression)}"
        }
        is com.funk.language.parser.TypeDeclaration -> {
            "type ${item.name} = ${item.definition}"
        }
        is com.funk.language.parser.EffectDeclaration -> {
            "effect ${item.name} { ... }"
        }
        is com.funk.language.parser.AbilityDeclaration -> {
            "ability ${item.name} { ... }"
        }
        is com.funk.language.parser.ModuleDeclaration -> {
            "module ${item.name} { ... }"
        }
        is com.funk.language.parser.ExposeDeclaration -> {
            "expose { ... }"
        }
        is com.funk.language.parser.AccessDeclaration -> {
            "access ${item.moduleName}"
        }
        else -> item.toString()
    }
}

private fun prettyPrintExpression(expr: com.funk.language.parser.Expression): String {
    return when (expr) {
        is com.funk.language.parser.Literal<*> -> expr.value.toString()
        is com.funk.language.parser.Identifier -> expr.name
        is com.funk.language.parser.LambdaExpression -> {
            val params = when (val p = expr.parameters) {
                is com.funk.language.parser.TypedParameters -> p.recordType.toString()
                is com.funk.language.parser.UntypedParameters -> "{ ${p.names.joinToString(", ")} }"
            }
            "$params -> ${prettyPrintExpression(expr.body)}"
        }
        is com.funk.language.parser.BinaryOperation -> {
            "${prettyPrintExpression(expr.left)} ${expr.operator} ${prettyPrintExpression(expr.right)}"
        }
        is com.funk.language.parser.FunctionCall -> {
            "${prettyPrintExpression(expr.function)} ${prettyPrintExpression(expr.arguments)}"
        }
        is com.funk.language.parser.RecordLiteral -> {
            val entries = expr.entries.joinToString(", ") { entry ->
                val value = entry.value
                if (value != null) {
                    "${entry.name} = ${prettyPrintExpression(value)}"
                } else {
                    entry.name  // Punned syntax
                }
            }
            "{ $entries }"
        }
        is com.funk.language.parser.IfExpression -> {
            "if ${prettyPrintExpression(expr.condition)} then ${prettyPrintExpression(expr.thenBranch)} else ${prettyPrintExpression(expr.elseBranch)}"
        }
        else -> expr.toString()
    }
}

