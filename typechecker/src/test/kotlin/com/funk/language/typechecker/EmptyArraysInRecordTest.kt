package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class EmptyArraysInRecordTest {

    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = FunkParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun `simple record with two empty arrays - just the type`() {
        val code = """
            let test = {yes = [], no = []}
            test
        """.trimIndent()

        println("Code: $code")
        val ast = parser.parse(code)
        println("Parsed successfully")

        val result = typeChecker.typeCheck(ast)

        // Extract the type of the 'test' expression (last item in the program)
        val testType = result.program.items.lastOrNull()?.let { item ->
            when (item) {
                is com.funk.language.ast.TopLevelExpression -> item.expression.inferredType
                is com.funk.language.ast.LetBinding -> item.inferredType
                else -> null
            }
        }

        val typeInfo = when (testType) {
            is com.funk.language.ast.FunkType.RecordType -> {
                "RecordType with fields: ${testType.fields.keys}"
            }
            is com.funk.language.ast.FunkType.VarType -> {
                "VarType(${testType.variable})"
            }
            is com.funk.language.ast.FunkType.ArrayType -> {
                "ArrayType(${testType.elementType})"
            }
            else -> {
                "${testType?.let { it::class.simpleName } ?: "null"}: $testType"
            }
        }

        // This test just validates that type checking succeeds and prints diagnostic info
        println("TEST OUTPUT: Type for {yes = [], no = []} is: $typeInfo")
    }

    @Test
    fun `simple record with two empty arrays`() {
        val code = """
            let test = {yes = [], no = []}
            test.no
        """.trimIndent()

        println("Code: $code")
        val ast = parser.parse(code)
        println("Parsed successfully")

        val result = typeChecker.typeCheck(ast)
        println("Type checked successfully!")
    }

    @Test
    fun `match expression with record in first case`() {
        val code = """
            let f = {x: Int} ->
                match x is
                  0 -> {yes = [], no = []}
                  _ -> {yes = [], no = []}
            let test = f {x = 0}
            test.no
        """.trimIndent()

        println("Code: $code")
        val ast = parser.parse(code)
        println("Parsed successfully")

        val result = typeChecker.typeCheck(ast)
        println("Type checked successfully!")
    }

    @Test
    fun `simple match with two empty array record cases`() {
        val code = """
            let f = {x: Int} ->
                match x is
                  0 -> {yes = [], no = []}
                  _ -> {yes = [1], no = [2]}
            let test = f {x = 0}
            test.no
        """.trimIndent()

        println("Code: $code")
        val ast = parser.parse(code)
        println("Parsed successfully")

        val result = typeChecker.typeCheck(ast)
        println("Type checked successfully!")
    }

    @Test
    fun `minimal recursive function with record return`() {
        val code = """
            let f = {n: Int} ->
                match n is
                  0 -> {yes = [], no = []}
                  _ -> do
                    let rest = f {n = n - 1}
                    rest

            f {n = 1}
        """.trimIndent()

        println("=".repeat(80))
        println("MINIMAL TEST CASE - returns record directly")
        println("=".repeat(80))
        println("Code: $code")
        val ast = parser.parse(code)
        println("Parsed successfully")

        val result = typeChecker.typeCheck(ast)
        println("Type checked successfully!")
    }

    @Test
    fun `minimal recursive function accessing single field`() {
        val code = """
            let f = {n: Int} ->
                match n is
                  0 -> {yes = [], no = []}
                  _ -> do
                    let rest = f {n = n - 1}
                    rest.no

            f {n = 1}
        """.trimIndent()

        println("=".repeat(80))
        println("MINIMAL TEST CASE - accesses single field")
        println("=".repeat(80))
        println("Code: $code")
        val ast = parser.parse(code)
        println("Parsed successfully")

        val result = typeChecker.typeCheck(ast)
        println("Type checked successfully!")
    }

    @Test
    fun `minimal recursive function rebuilding record`() {
        val code = """
            let f = {n: Int} ->
                match n is
                  0 -> {yes = [], no = []}
                  _ -> do
                    let rest = f {n = n - 1}
                    {yes = rest.yes, no = rest.no}

            f {n = 1}
        """.trimIndent()

        println("=".repeat(80))
        println("MINIMAL TEST CASE - rebuilds record from fields (yes first)")
        println("=".repeat(80))
        println("Code: $code")
        val ast = parser.parse(code)
        println("Parsed successfully")

        val result = typeChecker.typeCheck(ast)
        println("Type checked successfully!")
    }

    @Test
    fun `minimal recursive function rebuilding record reversed`() {
        val code = """
            let f = {n: Int} ->
                match n is
                  0 -> {yes = [], no = []}
                  _ -> do
                    let rest = f {n = n - 1}
                    {no = rest.no, yes = rest.yes}

            f {n = 1}
        """.trimIndent()

        println("=".repeat(80))
        println("MINIMAL TEST CASE - rebuilds record from fields (no first)")
        println("=".repeat(80))
        println("Code: $code")
        val ast = parser.parse(code)
        println("Parsed successfully")

        val result = typeChecker.typeCheck(ast)
        println("Type checked successfully!")
    }

    @Test
    fun `simple recursive function with record return`() {
        val code = """
            let count_down = {n: Int} ->
                match n is
                  0 -> {yes = [], no = []}
                  _ -> do
                    let rest = count_down {n = n - 1}
                    {yes = rest.yes, no = rest.no}

            count_down {n = 5}
        """.trimIndent()

        println("Code: $code")
        val ast = parser.parse(code)
        println("Parsed successfully")

        val result = typeChecker.typeCheck(ast)
        println("Type checked successfully!")
    }

    @Test
    fun `user example - recursive with two field accesses`() {
        val code = """
            use std::collections::array
            let foo = {n: Int} ->
              match n is
                0 -> {x = [], y = []}
                _ -> do
                  let k = foo {n = n - 1}
                  let x = array::of {x = n, xs = k.x}
                  let y = array::of {x = n, xs = k.y}
                  {x, y}

            foo {n = 5}
        """.trimIndent()

        println("Code: $code")
        val ast = parser.parse(code)
        println("Parsed successfully")

        val result = typeChecker.typeCheck(ast)
        println("Type checked successfully!")
    }

    @Test
    fun `exact partition function from interpreter test`() {
        val code = """
            use std::collections::array
            let my_partition = {pred: {x: Int} -> Bool, xs: [Int]} ->
                match xs is
                  [] -> {yes = [], no = []}
                  [head, ...tail] -> do
                    let rest = my_partition {pred = pred, xs = tail}
                    if pred {x = head}
                    then {yes = array::of {x = head, xs = rest.yes}, no = rest.no}
                    else {yes = rest.yes, no = array::of {x = head, xs = rest.no}}

            let is_even = {x: Int} -> x % 2 == 0
            my_partition {pred = is_even, xs = [1, 2, 3, 4, 5, 6]}
        """.trimIndent()

        println("Code: $code")
        val ast = parser.parse(code)
        println("Parsed successfully")

        val result = typeChecker.typeCheck(ast)
        println("Type checked successfully!")
    }
}
