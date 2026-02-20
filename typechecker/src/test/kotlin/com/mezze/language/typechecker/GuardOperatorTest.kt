package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull

/**
 * Tests for guard operators (&& and ||) in match expressions
 */
class GuardOperatorTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun typeCheck(code: String) {
        val program = parser.parse(code)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `guard with && operator`() {
        val code = """
            let check = {x: Int, y: Int} ->
                match x is
                    n if n > 0 && y > 0 -> "both positive"
                    _ -> "not both positive"
            let result = check {x = 5, y = 10}
        """.trimIndent()

        typeCheck(code)
    }

    @Test
    fun `guard with || operator`() {
        val code = """
            let check = {x: Int} ->
                match x is
                    n if n < 0 || n > 100 -> "out of range"
                    _ -> "in range"
            let result = check {x = 50}
        """.trimIndent()

        typeCheck(code)
    }

    @Test
    fun `guard with complex boolean expression`() {
        val code = """
            let check = {x: Int, y: Int, z: Int} ->
                match x is
                    n if (n > 0 && y > 0) || z == 0 -> "condition met"
                    _ -> "condition not met"
            let result = check {x = 5, y = 10, z = 0}
        """.trimIndent()

        typeCheck(code)
    }

    @Test
    fun `guard with string comparison`() {
        val code = """
            let classify = {s: String} ->
                match s is
                    "$(name)" if name == "admin" || name == "root" -> "privileged"
                    "$(name)" -> "regular: $(name)"
                    _ -> "unknown"
            let result = classify {s = "admin"}
        """.trimIndent()

        typeCheck(code)
    }

    @Test
    fun `guard with && chaining multiple conditions`() {
        val code = """
            let validate = {x: Int, y: Int, z: Int} ->
                match x is
                    n if n > 0 && y > 0 && z > 0 -> "all positive"
                    n if n > 0 && y > 0 -> "x and y positive"
                    n if n > 0 -> "only x positive"
                    _ -> "none positive"
            let result = validate {x = 1, y = 2, z = 3}
        """.trimIndent()

        typeCheck(code)
    }

    @Test
    fun `guard with not operator`() {
        val code = """
            let check = {x: Int} ->
                match x is
                    n if !(n == 0) -> "non-zero"
                    _ -> "zero"
            let result = check {x = 5}
        """.trimIndent()

        typeCheck(code)
    }

    @Test
    fun `guard with && and pattern binding`() {
        val code = """
            let parse_and_validate = {s: String} ->
                match s is
                    "$(key)=$(value)" if key != "" && value != "" -> Some {val = {k = key, v = value}}
                    _ -> None
            let result = parse_and_validate {s = "name=Alice"}
        """.trimIndent()

        typeCheck(code)
    }

    @Test
    fun `guard with || and pattern binding`() {
        val code = """
            let is_special = {s: String} ->
                match s is
                    "$(name)" if name == "admin" || name == "root" || name == "superuser" -> True
                    _ -> False
            let result = is_special {s = "admin"}
        """.trimIndent()

        typeCheck(code)
    }
}
