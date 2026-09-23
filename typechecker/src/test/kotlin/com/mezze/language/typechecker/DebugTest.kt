package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * The three arithmetic operators infer a usable signature from an unannotated
 * lambda.
 *
 * This was a debug script: it typechecked the source inside a `try`, printed the
 * inferred type, and swallowed any exception — so it could not fail, and a broken
 * inference here would have shown up only as different console output nobody
 * reads. The scenario is worth keeping; the swallowing is not.
 */
class DebugTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun `unannotated arithmetic lambdas infer a two-field function type`() {
        val source = """
            let add = {a, b} -> a + b
            let multiply = {x, y} -> x * y
            let divide = {a, b} -> a / b
        """.trimIndent()

        // No try/catch: a failure to typecheck IS the failure this test reports.
        val result = typeChecker.typeCheck(parser.parseParsedModule(source, "test.mz"), Paths.get("."))

        for ((name, fields) in listOf(
            "add" to listOf("a", "b"),
            "multiply" to listOf("x", "y"),
            "divide" to listOf("a", "b"),
        )) {
            val t = result.getVariableType(name)
            assertNotNull(t, "`$name` has no inferred type")
            assertTrue(t is MezzeType.FunctionType, "`$name` should be a function, got $t")
            val param = (t as MezzeType.FunctionType).paramType
            assertTrue(param is MezzeType.RecordType, "`$name`'s parameter should be a record, got $param")
            assertTrue(
                (param as MezzeType.RecordType).fields.keys == fields.toSet(),
                "`$name` should take { ${fields.joinToString(", ")} }, got ${param.fields.keys}"
            )
        }
    }
}
