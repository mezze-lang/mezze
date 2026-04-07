package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class AstDumpTest {

    @Test
    fun `dump v1 AST for 1 + 2`() {
        val parser = MezzeParser()
        val parsedModule = parser.parseParsedModule("1 + 2", "test.mz")
        val tc = TypeChecker()
        val result = tc.typeCheck(parsedModule, Paths.get("."))

        println("=== TypedModule for '1 + 2' ===")
        println("decls: ${result.typedModule?.decls}")
    }

    @Test
    fun `dump v1 AST for multi-arg function`() {
        val parser = MezzeParser()
        val parsedModule = parser.parseParsedModule("let add = {x, y} -> x + y\nadd { x = 10, y = 32 }", "test.mz")
        val tc = TypeChecker()
        val result = tc.typeCheck(parsedModule, Paths.get("."))
        println("=== TypedModule for multi-arg function ===")
        val addType = result.getVariableType("add")
        println("add type: $addType")
    }

    @Test
    fun `dump effect handler test`() {
        val parser = MezzeParser()
        val parsedModule = parser.parseParsedModule("""
            use std::effects::log::{Log, Console, println}
            perform Console in do
                println { msg = "hello" }
                42
        """.trimIndent(), "test.mz")
        val tc = TypeChecker()
        val result = tc.typeCheck(parsedModule, Paths.get("."))
        println("=== Effect handler test ===")
        println("typedModule decls: ${result.typedModule?.decls?.size}")
    }

    @Test
    fun `dump lambda body for x + y`() {
        val parser = MezzeParser()
        val parsedModule = parser.parseParsedModule("let add = {x, y} -> x + y\nadd { x = 10, y = 32 }", "test.mz")
        val tc = TypeChecker()
        val result = tc.typeCheck(parsedModule, Paths.get("."))
        val addType = result.getVariableType("add")
        println("=== Lambda type for {x,y} -> x + y ===")
        println("add type: $addType")
    }

    @Test
    fun `dump v1 AST for function call`() {
        val parser = MezzeParser()
        val parsedModule = parser.parseParsedModule("let double = {x} -> x * 2\ndouble { x = 21 }", "test.mz")
        val tc = TypeChecker()
        val result = tc.typeCheck(parsedModule, Paths.get("."))

        println("=== TypedModule for 'let double; double {x=21}' ===")
        val doubleType = result.getVariableType("double")
        println("double type: $doubleType")
    }
}
