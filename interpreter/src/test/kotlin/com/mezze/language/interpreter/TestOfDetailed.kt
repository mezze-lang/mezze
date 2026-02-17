package com.mezze.language.interpreter

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.SAME_THREAD)
class TestOfDetailed {

    @BeforeEach
    fun setup() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    @AfterEach
    fun teardown() {
        TruffleContextHolder.clearEnv()
    }
    @Test
    fun testSimpleOf() {
        println("\n=== TEST 1: Simple of call ===")
        val source1 = """
            use std::collections::array::{of}

            of { x = 1, xs = [] }
        """.trimIndent()

        val parser = MezzeParser()
        val typeChecker = TypeChecker()
        val program = parser.parse(source1)
        val typeCheckResult = typeChecker.typeCheck(program)

        val result = MezzeTestHelper.execute(
            typeCheckResult.program,
            typeCheckResult.monomorphicInstances,
            java.nio.file.Paths.get(System.getProperty("mezze.project.root", TestUtils.findProjectRoot().toString()))
        )

        println("Result type: ${result::class.simpleName}")
        if (result is MezzeTruffleFunction) {
            println("Function name: ${result.name}")
            println("Closure vars: ${result.closureVariables.keys}")
        }
    }

    @Test
    fun testNestedOf() {
        println("\n=== TEST 2: Nested of calls ===")
        val source2 = """
            use std::collections::array::{of}

            let inner = of { x = 3, xs = [] }
            let middle = of { x = 2, xs = inner }
            let outer = of { x = 1, xs = middle }
            outer
        """.trimIndent()

        val parser = MezzeParser()
        val typeChecker = TypeChecker()
        val program = parser.parse(source2)
        val typeCheckResult = typeChecker.typeCheck(program)

        val result = MezzeTestHelper.execute(
            typeCheckResult.program,
            typeCheckResult.monomorphicInstances,
            java.nio.file.Paths.get(System.getProperty("mezze.project.root", TestUtils.findProjectRoot().toString()))
        )

        println("Result type: ${result::class.simpleName}")
        if (result is MezzeTruffleFunction) {
            println("Function name: ${result.name}")
            println("Closure vars: ${result.closureVariables.keys}")
        } else if (result is MezzeArrayList) {
            println("Array size: ${result.size()}")
        }
    }

    @Test
    fun testInlineNestedOf() {
        println("\n=== TEST 3: Inline nested of (like failing test) ===")
        val source3 = """
            use std::collections::array::{of}

            of { x = 1, xs = of { x = 2, xs = of { x = 3, xs = [] } } }
        """.trimIndent()

        val parser = MezzeParser()
        val typeChecker = TypeChecker()
        val program = parser.parse(source3)
        val typeCheckResult = typeChecker.typeCheck(program)

        val result = MezzeTestHelper.execute(
            typeCheckResult.program,
            typeCheckResult.monomorphicInstances,
            java.nio.file.Paths.get(System.getProperty("mezze.project.root", TestUtils.findProjectRoot().toString()))
        )

        println("Result type: ${result::class.simpleName}")
        if (result is MezzeTruffleFunction) {
            println("Function name: ${result.name}")
            println("Closure vars: ${result.closureVariables.keys}")
        } else if (result is MezzeArrayList) {
            println("Array size: ${result.size()}")
        }
    }

    @Test
    fun testWithLetBinding() {
        println("\n=== TEST 4: With let binding (exact failing test pattern) ===")
        val source4 = """
            use std::collections::array::{of}

            let arr = of { x = 1, xs = of { x = 2, xs = of { x = 3, xs = [] } } }
            arr
        """.trimIndent()

        val parser = MezzeParser()
        val typeChecker = TypeChecker()
        val program = parser.parse(source4)
        val typeCheckResult = typeChecker.typeCheck(program)

        val result = MezzeTestHelper.execute(
            typeCheckResult.program,
            typeCheckResult.monomorphicInstances,
            java.nio.file.Paths.get(System.getProperty("mezze.project.root", TestUtils.findProjectRoot().toString()))
        )

        println("Result type: ${result::class.simpleName}")
        if (result is MezzeTruffleFunction) {
            println("Function name: ${result.name}")
            println("Closure vars: ${result.closureVariables.keys}")
        } else if (result is MezzeArrayList) {
            println("Array size: ${result.size()}")
        }
    }

    @Test
    fun testWithFromArray() {
        println("\n=== TEST 5: Exact failing test reproduction ===")
        val source5 = """
            use std::collections::array::{of}
            use std::collections::list::{from_array, length}

            let arr = of { x = 1, xs = of { x = 2, xs = of { x = 3, xs = [] } } }
            let xs = from_array { xs = arr }

            length { xs = xs }
        """.trimIndent()

        val parser = MezzeParser()
        val typeChecker = TypeChecker()
        val program = parser.parse(source5)
        val typeCheckResult = typeChecker.typeCheck(program)

        val result = MezzeTestHelper.execute(
            typeCheckResult.program,
            typeCheckResult.monomorphicInstances,
            java.nio.file.Paths.get(System.getProperty("mezze.project.root", TestUtils.findProjectRoot().toString()))
        )

        println("Result type: ${result::class.simpleName}")
        if (result is MezzeInt) {
            println("Result value: ${result.value}")
        } else if (result is MezzeTruffleFunction) {
            println("Function name: ${result.name}")
            println("Closure vars: ${result.closureVariables.keys}")
        }
    }

    @Test
    fun testWithJustFromArrayImport() {
        println("\n=== TEST 6: Just import from_array, check arr type ===")
        val source6 = """
            use std::collections::array::{of}
            use std::collections::list::{from_array}

            let arr = of { x = 1, xs = of { x = 2, xs = of { x = 3, xs = [] } } }
            arr
        """.trimIndent()

        val parser = MezzeParser()
        val typeChecker = TypeChecker()
        val program = parser.parse(source6)
        val typeCheckResult = typeChecker.typeCheck(program)

        val result = MezzeTestHelper.execute(
            typeCheckResult.program,
            typeCheckResult.monomorphicInstances,
            java.nio.file.Paths.get(System.getProperty("mezze.project.root", TestUtils.findProjectRoot().toString()))
        )

        println("Result type: ${result::class.simpleName}")
        if (result is MezzeTruffleFunction) {
            println("Function name: ${result.name}")
            println("Closure vars: ${result.closureVariables.keys}")
        } else if (result is MezzeArrayList) {
            println("Array size: ${result.size()}")
        }
    }
}
