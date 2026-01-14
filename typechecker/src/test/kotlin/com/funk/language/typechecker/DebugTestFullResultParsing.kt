package com.funk.language.typechecker

import org.junit.jupiter.api.Test
import java.io.File

class DebugTestFullResultParsing {

    @Test
    fun `debug function extraction logic`() {
        val resultFile = File("/Users/shriyans/Projects/funk/stdlib/std/result.funk")
        val lines = resultFile.readLines()

        val functions = mutableListOf<String>()
        var currentFunc = mutableListOf<String>()
        var inFunction = false

        for (line in lines) {
            if (line.trim().startsWith("pub let ")) {
                // Save previous function if we have one
                if (currentFunc.isNotEmpty()) {
                    functions.add(currentFunc.joinToString("\n"))
                }
                currentFunc = mutableListOf(line)
                inFunction = true
            } else if (inFunction) {
                if (line.trim().isEmpty()) {
                    // Empty line marks end of function
                    if (currentFunc.isNotEmpty()) {
                        functions.add(currentFunc.joinToString("\n"))
                        currentFunc = mutableListOf()
                        inFunction = false
                    }
                } else {
                    // Non-empty line - add to current function
                    currentFunc.add(line)
                }
            }
        }
        // Don't forget the last function if file doesn't end with blank line
        if (currentFunc.isNotEmpty()) {
            functions.add(currentFunc.joinToString("\n"))
        }

        println("Found ${functions.size} function declarations\n")

        for ((index, func) in functions.withIndex()) {
            val funcName = func.lines().first().substringAfter("let ").substringBefore(":").trim()
            println("=".repeat(80))
            println("[$index] Function: $funcName")
            println("=".repeat(80))
            println(func)
            println()
        }
    }
}
