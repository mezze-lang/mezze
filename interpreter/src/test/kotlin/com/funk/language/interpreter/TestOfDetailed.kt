package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test

class TestOfDetailed {
    @Test
    fun testSimpleOf() {
        println("\n=== TEST 1: Simple of call ===")
        val source1 = """
            use std::collections::array::{of}

            of { x = 1, xs = [] }
        """.trimIndent()

        val parser = FunkParser()
        val typeChecker = TypeChecker()
        val program = parser.parse(source1)
        val typeCheckResult = typeChecker.typeCheck(program)

        val result = FunkTestHelper.execute(
            typeCheckResult.program,
            typeCheckResult.monomorphicInstances,
            java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk"))
        )

        println("Result type: ${result::class.simpleName}")
        if (result is FunkTruffleFunction) {
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

        val parser = FunkParser()
        val typeChecker = TypeChecker()
        val program = parser.parse(source2)
        val typeCheckResult = typeChecker.typeCheck(program)

        val result = FunkTestHelper.execute(
            typeCheckResult.program,
            typeCheckResult.monomorphicInstances,
            java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk"))
        )

        println("Result type: ${result::class.simpleName}")
        if (result is FunkTruffleFunction) {
            println("Function name: ${result.name}")
            println("Closure vars: ${result.closureVariables.keys}")
        } else if (result is FunkArrayList) {
            println("Array size: ${result.elements.size}")
        }
    }

    @Test
    fun testInlineNestedOf() {
        println("\n=== TEST 3: Inline nested of (like failing test) ===")
        val source3 = """
            use std::collections::array::{of}

            of { x = 1, xs = of { x = 2, xs = of { x = 3, xs = [] } } }
        """.trimIndent()

        val parser = FunkParser()
        val typeChecker = TypeChecker()
        val program = parser.parse(source3)
        val typeCheckResult = typeChecker.typeCheck(program)

        val result = FunkTestHelper.execute(
            typeCheckResult.program,
            typeCheckResult.monomorphicInstances,
            java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk"))
        )

        println("Result type: ${result::class.simpleName}")
        if (result is FunkTruffleFunction) {
            println("Function name: ${result.name}")
            println("Closure vars: ${result.closureVariables.keys}")
        } else if (result is FunkArrayList) {
            println("Array size: ${result.elements.size}")
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

        val parser = FunkParser()
        val typeChecker = TypeChecker()
        val program = parser.parse(source4)
        val typeCheckResult = typeChecker.typeCheck(program)

        val result = FunkTestHelper.execute(
            typeCheckResult.program,
            typeCheckResult.monomorphicInstances,
            java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk"))
        )

        println("Result type: ${result::class.simpleName}")
        if (result is FunkTruffleFunction) {
            println("Function name: ${result.name}")
            println("Closure vars: ${result.closureVariables.keys}")
        } else if (result is FunkArrayList) {
            println("Array size: ${result.elements.size}")
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

        val parser = FunkParser()
        val typeChecker = TypeChecker()
        val program = parser.parse(source5)
        val typeCheckResult = typeChecker.typeCheck(program)

        val result = FunkTestHelper.execute(
            typeCheckResult.program,
            typeCheckResult.monomorphicInstances,
            java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk"))
        )

        println("Result type: ${result::class.simpleName}")
        if (result is FunkInt) {
            println("Result value: ${result.value}")
        } else if (result is FunkTruffleFunction) {
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

        val parser = FunkParser()
        val typeChecker = TypeChecker()
        val program = parser.parse(source6)
        val typeCheckResult = typeChecker.typeCheck(program)

        val result = FunkTestHelper.execute(
            typeCheckResult.program,
            typeCheckResult.monomorphicInstances,
            java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk"))
        )

        println("Result type: ${result::class.simpleName}")
        if (result is FunkTruffleFunction) {
            println("Function name: ${result.name}")
            println("Closure vars: ${result.closureVariables.keys}")
        } else if (result is FunkArrayList) {
            println("Array size: ${result.elements.size}")
        }
    }
}
