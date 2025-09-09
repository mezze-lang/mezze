package com.funk.language.cli

import com.funk.language.parser.FunkParserImpl
import com.funk.language.typechecker.*
import com.funk.language.interpreter.FunkException
import com.funk.language.interpreter.ASTToTruffleConverter
import com.funk.language.interpreter.FunkLanguage
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Source
import java.io.File
import java.nio.file.Path

fun main(args: Array<String>) {
    // Handle global flags first
    if (args.contains("--help") || args.contains("-h")) {
        showHelp()
        return
    }
    
    if (args.contains("--version") || args.contains("-v")) {
        showVersion()
        return
    }
    
    if (args.isEmpty()) {
        showUsage()
        kotlin.system.exitProcess(1)
    }

    val command = args[0]
    val remainingArgs = args.drop(1)
    
    // Handle command-specific help
    if (remainingArgs.contains("--help") || remainingArgs.contains("-h")) {
        showCommandHelp(command)
        return
    }
    
    try {
        when (command) {
            "parse" -> handleParseCommand(remainingArgs)
            "check" -> handleCheckCommand(remainingArgs)
            "exec" -> handleExecCommand(remainingArgs)
            else -> {
                println("❌ Unknown command: $command")
                println()
                showUsage()
                kotlin.system.exitProcess(1)
            }
        }
    } catch (e: StructuredTypeError) {
        println("❌ Type Error:")
        println(ErrorPrettyPrinter.prettyPrintErrorSimple(e))
        kotlin.system.exitProcess(1)
    } catch (e: Exception) {
        println("❌ Error:")
        println(e.message ?: "Unknown error")
        kotlin.system.exitProcess(1)
    }
}

/**
 * Show main help information
 */
private fun showHelp() {
    println("🚀 Funk Language v1.0.0")
    println("A modern functional programming language with full Truffle support")
    println()
    showUsage()
    println()
    println("COMMANDS:")
    println("  parse     Parse Funk code and show AST")
    println("  check     Type check Funk code and show inferred types")
    println("  exec      Execute Funk code using Truffle runtime")
    println()
    println("GLOBAL OPTIONS:")
    println("  -h, --help     Show this help message")
    println("  -v, --version  Show version information")
    println()
    println("EXAMPLES:")
    println("  funk parse program.funk              # Parse a file")
    println("  echo 'let x = 5 + 10' | funk exec    # Execute from stdin")
    println("  funk check --help                    # Get help for check command")
    println()
    println("For command-specific help: funk <command> --help")
}

/**
 * Show version information
 */
private fun showVersion() {
    println("🚀 Funk Language v1.0.0")
    println("Built with Oracle GraalVM 24.0.2")
    println()
    println("Features:")
    println("  ✅ Bidirectional type inference")
    println("  ✅ Pattern matching")
    println("  ✅ Record types with row polymorphism")
    println("  ✅ Variant types")
    println("  ✅ Full Truffle polyglot interop")
}

/**
 * Show basic usage
 */
private fun showUsage() {
    println("USAGE:")
    println("  funk <command> [options] [file]")
    println()
    println("COMMANDS: parse, check, exec")
    println("For command-specific help: funk <command> --help")
}

/**
 * Show command-specific help
 */
private fun showCommandHelp(command: String) {
    when (command) {
        "parse" -> {
            println("USAGE: funk parse [file]")
            println()
            println("Parse Funk source code and display the Abstract Syntax Tree")
            println()
            println("OPTIONS:")
            println("  -h, --help         Show this help message")
            println()
            println("EXAMPLES:")
            println("  funk parse program.funk       # Parse a file")
            println("  echo 'let x = 5' | funk parse # Parse from stdin")
        }
        
        "check" -> {
            println("USAGE: funk check [file]")
            println()
            println("Type check Funk source code and show inferred types")
            println()
            println("OPTIONS:")
            println("  -h, --help         Show this help message")
            println()
            println("EXAMPLES:")
            println("  funk check program.funk       # Type check a file")
            println("  echo 'let x = 5 + 10' | funk check  # Type check from stdin")
        }
        
        "exec" -> {
            println("USAGE: funk exec [file]")
            println()
            println("Execute Funk source code using the Truffle runtime")
            println()
            println("OPTIONS:")
            println("  -h, --help         Show this help message")
            println()
            println("EXAMPLES:")
            println("  funk exec program.funk              # Execute a file")
            println("  echo 'let x = 5 + 10' | funk exec   # Execute from stdin")
        }
        
        else -> {
            println("❌ Unknown command: $command")
            println()
            showUsage()
        }
    }
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
            showUsage()
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
    val (source, filePath) = when {
        args.isEmpty() -> {
            // Read from stdin
            Pair(readStdin(), null)
        }
        args.size == 1 -> {
            // Read from file
            val file = File(args[0])
            if (!file.exists()) {
                println("Error: File not found at '${args[0]}'")
                return
            }
            Pair(file.readText(), file.toPath())
        }
        else -> {
            println("Error: check command takes at most 1 argument (filename)")
            showUsage()
            return
        }
    }

    try {
        val parser = FunkParserImpl()
        val ast = parser.parse(source)
        
        // Use module-aware type checking if we have a file path
        val result = if (filePath != null) {
        val typeChecker = TypeChecker()
            typeChecker.typeCheck(ast, filePath.parent ?: filePath)
        } else {
            // Fallback to simple type checking for stdin
            val typeChecker = TypeChecker()
            typeChecker.typeCheck(ast)
        }
        
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
            when (dataType) {
                null -> name
                is FunkType.RecordType -> {
                    // For record types, format as "Red { value: Int }" without extra braces
                    val fields = dataType.fields.entries.joinToString(", ") { (fieldName, fieldType) ->
                        "$fieldName: ${prettyPrintType(fieldType)}"
                    }
                    val openMarker = if (dataType.rowVar != null) ", ..." else ""
                    "$name { $fields$openMarker }"
                }
                else -> "$name(${prettyPrintType(dataType)})" // Ok(Int) for non-record types
            }
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

private fun handleExecCommand(args: List<String>) {
    val (source, filePath) = when {
        args.isEmpty() -> {
            // Read from stdin
            Pair(readStdin(), null)
        }
        args.size == 1 -> {
            // Read from file
            val file = File(args[0])
            if (!file.exists()) {
                println("Error: File not found at '${args[0]}'")
                return
            }
            Pair(file.readText(), file.toPath())
        }
        else -> {
            println("Error: exec command takes at most 1 argument (filename)")
            showUsage()
            return
        }
    }

    try {
        // Create Polyglot context with explicit language configuration
        val engine = Engine.newBuilder()
            .allowExperimentalOptions(true)
            .build()
            
        println("Available languages: ${engine.languages.keys}")
        
        val context = Context.newBuilder("funk")
            .engine(engine)
            .allowAllAccess(true)
            .allowExperimentalOptions(true)
            .build()
        
        try {
            val polyglotSource = Source.newBuilder("funk", source, filePath?.toString() ?: "<stdin>").build()
            val result = context.eval(polyglotSource)
            
            println("✅ Execution successful!")
            println()
            println("Result: ${result.toString()}")
        } finally {
            context.close()
            engine.close()
        }
        
    } catch (e: StructuredTypeError) {
        println("❌ Type Error:")
        println(ErrorPrettyPrinter.prettyPrintErrorSimple(e))
        kotlin.system.exitProcess(1)
    } catch (e: FunkException) {
        println("❌ Runtime Error:")
        println(e.message ?: "Unknown runtime error")
        kotlin.system.exitProcess(1)
    } catch (e: Exception) {
        println("❌ Error:")
        println(e.message ?: "Unknown error")
        kotlin.system.exitProcess(1)
    }
}



