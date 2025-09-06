package com.funk.language.cli

import com.funk.language.parser.FunkParserImpl
import com.funk.language.typechecker.*
import com.funk.language.interpreter.FunkException
import com.funk.language.interpreter.ASTToTruffleConverter
import com.funk.language.interpreter.FunkLanguage
import com.funk.language.compiler.*
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import java.io.File
import java.nio.file.Path

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        return
    }

    when (args[0]) {
        "parse" -> handleParseCommand(args.drop(1))
        "check" -> handleCheckCommand(args.drop(1))
        "exec" -> handleExecCommand(args.drop(1))
        "compile" -> handleCompileCommand(args.drop(1))
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
    println("  funk exec [file]     - Execute a file (or stdin if no file) and show the result")
    println("  funk compile [options] [file] - Compile a file (or stdin if no file) to target language")
    println()
    println("Compile options:")
    println("  --target js|jvm|llvm|c  - Target language (default: js)")
    println("  --output <file>         - Output file (default: stdout)")
    println("  --optimize <level>      - Optimization level 0-3 (default: 1)")
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
            printUsage()
            return
        }
    }

    try {
        val parser = FunkParserImpl()
        val ast = parser.parse(source)
        
        // Use module-aware type checking if we have a file path
        val result = if (filePath != null) {
            val moduleManager = RustModuleManager()
            val typeChecker = TypeChecker(moduleManager)
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
            printUsage()
            return
        }
    }

    try {
        // For files with module imports, we need to use our module-aware compilation
        // For now, let's use the simple polyglot API and improve module support later
        val polyglotContext = Context.newBuilder("funk")
            .allowAllAccess(true)
            .build()
        
        try {
            val polyglotSource = Source.newBuilder("funk", source, filePath?.toString() ?: "<stdin>").build()
            val result = polyglotContext.eval(polyglotSource)
            
            println("✅ Execution successful!")
            println()
            println("Result: ${result.toString()}")
        } finally {
            polyglotContext.close()
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

private fun handleCompileCommand(args: List<String>) {
    // Parse command line options
    var target = CompilationTarget.JavaScript
    var outputFile: String? = null
    var optimizationLevel = OptimizationLevel.O1
    var sourceFile: String? = null
    
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--target" -> {
                if (i + 1 >= args.size) {
                    println("Error: --target requires a value")
                    return
                }
                target = when (args[i + 1].lowercase()) {
                    "js", "javascript" -> CompilationTarget.JavaScript
                    "jvm", "java" -> CompilationTarget.JVM
                    "llvm" -> CompilationTarget.LLVM
                    "c" -> CompilationTarget.C
                    else -> {
                        println("Error: Unknown target '${args[i + 1]}'. Supported: js, jvm, llvm, c")
                        return
                    }
                }
                i += 2
            }
            "--output" -> {
                if (i + 1 >= args.size) {
                    println("Error: --output requires a file path")
                    return
                }
                outputFile = args[i + 1]
                i += 2
            }
            "--optimize" -> {
                if (i + 1 >= args.size) {
                    println("Error: --optimize requires a level (0-3)")
                    return
                }
                optimizationLevel = when (args[i + 1]) {
                    "0" -> OptimizationLevel.O0
                    "1" -> OptimizationLevel.O1
                    "2" -> OptimizationLevel.O2
                    "3" -> OptimizationLevel.O3
                    else -> {
                        println("Error: Invalid optimization level '${args[i + 1]}'. Use 0-3")
                        return
                    }
                }
                i += 2
            }
            else -> {
                if (args[i].startsWith("--")) {
                    println("Error: Unknown option '${args[i]}'")
                    return
                }
                if (sourceFile == null) {
                    sourceFile = args[i]
                } else {
                    println("Error: Multiple source files not supported")
                    return
                }
                i++
            }
        }
    }
    
    val (source, filePath) = when {
        sourceFile == null -> {
            // Read from stdin
            Pair(readStdin(), null)
        }
        else -> {
            // Read from file
            val file = File(sourceFile)
            if (!file.exists()) {
                println("Error: File not found at '$sourceFile'")
                return
            }
            Pair(file.readText(), file.toPath())
        }
    }
    
    try {
        // Parse
        val parser = FunkParserImpl()
        val ast = parser.parse(source)
        
        // Type check with module resolution
        val typedProgram = if (filePath != null) {
            val moduleManager = RustModuleManager()
            val typeChecker = TypeChecker(moduleManager)
            typeChecker.typeCheck(ast, filePath.parent ?: filePath)
        } else {
            // Fallback to simple type checking for stdin
            val typeChecker = TypeChecker()
            typeChecker.typeCheck(ast)
        }
        
        // Compile
        val compiler = FunkCompiler()
        val options = CompilerOptions(
            optimizationLevel = optimizationLevel,
            includeDebugInfo = false
        )
        
        val result = compiler.compile(typedProgram, target, options)
        
        when (result) {
            is CompilationResult.Success -> {
                println("✅ Compilation successful!")
                println()
                println("Target: ${result.target}")
                println("Generated ${result.metadata.generatedLines} lines")
                println("Optimization: ${result.metadata.optimizationLevel}")
                println()
                
                if (outputFile != null) {
                    File(outputFile).writeText(result.code)
                    println("Output written to: $outputFile")
                } else {
                    println("Generated code:")
                    println("=" + "=".repeat(49))
                    println(result.code)
                }
            }
            is CompilationResult.Error -> {
                println("❌ Compilation failed:")
                println(result.message)
                result.cause?.printStackTrace()
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
        e.printStackTrace()
        kotlin.system.exitProcess(1)
    }
}

