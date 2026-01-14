package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class SimpleHigherOrderTest {

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
    fun `test simple recursive with higher-order param - no array patterns`() {
        val code = """
            let apply_n = {f: {x: Int} -> Int, n: Int, x: Int} ->
                if n <= 0
                then x
                else apply_n {f = f, n = n - 1, x = f {x = x}}

            let inc = {x: Int} -> x + 1
            let result = apply_n {f = inc, n = 5, x = 0}
        """.trimIndent()

        println("Starting typecheck...")
        val program = parser.parse(code)
        val result = typeChecker.typeCheck(program)
        println("SUCCESS")
    }
}
