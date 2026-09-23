package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * An interpolation hole DEMANDS a `Str`, and that demand reaches the parameter
 * it is built from.
 *
 * `{first, second} -> "$(first)$(second)"` therefore infers
 * `{ first: Str, second: Str } -> Str` — the holes are the only thing typing the
 * two parameters, so if the demand did not propagate they would stay type
 * variables and the function would wrongly accept anything.
 *
 * This was a debug script that typechecked inside a `try`, printed the inferred
 * parameter types, and swallowed any exception — so it could not fail, and the
 * propagation it was written to watch was never actually checked.
 */
class StringInterpolationDebugTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun paramFieldsOf(source: String, binding: String): Map<String, MezzeType> {
        val result = typeChecker.typeCheck(parser.parseParsedModule(source, "test.mz"), Paths.get("."))
        val t = result.getVariableType(binding)
        assertNotNull(t, "`$binding` has no inferred type")
        assertTrue(t is MezzeType.FunctionType, "`$binding` should be a function, got $t")
        val param = (t as MezzeType.FunctionType).paramType
        assertTrue(param is MezzeType.RecordType, "`$binding`'s parameter should be a record, got $param")
        return (param as MezzeType.RecordType).fields
    }

    @Test
    fun `interpolated parameters are typed Str by the holes that use them`() {
        val fields = paramFieldsOf(
            """
            let concat = {first, second} -> "${'$'}(first)${'$'}(second)"
            concat
            """.trimIndent(),
            "concat",
        )
        for (name in listOf("first", "second")) {
            val t = fields[name]
            assertTrue(
                t is MezzeType.StringType,
                "`$name` is only used inside an interpolation hole, so it must be Str — " +
                    "got ${t?.let { prettyPrintType(it) } ?: "nothing"}"
            )
        }
    }

    @Test
    fun `a hole among literal text types its parameter too`() {
        val fields = paramFieldsOf(
            """
            let format = {name, age} -> "Name: ${'$'}(name), Age: ${'$'}(age)"
            format
            """.trimIndent(),
            "format",
        )
        for (name in listOf("name", "age")) {
            val t = fields[name]
            assertTrue(
                t is MezzeType.StringType,
                "`$name` sits in an interpolation hole surrounded by literal text and must " +
                    "still be Str — got ${t?.let { prettyPrintType(it) } ?: "nothing"}"
            )
        }
    }

    @Test
    fun `the interpolated function returns Str`() {
        val result = typeChecker.typeCheck(
            parser.parseParsedModule(
                """
                let concat = {first, second} -> "${'$'}(first)${'$'}(second)"
                concat
                """.trimIndent(),
                "test.mz",
            ),
            Paths.get("."),
        )
        val t = result.getVariableType("concat") as MezzeType.FunctionType
        assertTrue(t.returnType is MezzeType.StringType, "an interpolated literal is a Str, got ${t.returnType}")
    }
}
