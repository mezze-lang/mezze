package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * An OPEN parameter accepts a record carrying extra fields, and a CLOSED one
 * does not.
 *
 * That is the whole point of the `...` in `{x: Int, y: Int, ...}`, and it is the
 * permissive half of the rule whose strict half is tested in
 * [OpenRecordDemandLaunderTest] — an open row may absorb fields the caller
 * supplies, but never invent ones the caller does not.
 *
 * This was a debug script: it printed the source, typechecked inside a `try`, and
 * printed either "Type checking succeeded!" or the exception. Both outcomes were
 * a pass.
 */
class OpenRecordDebugTest {

    private lateinit var parser: MezzeParser

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
    }

    private fun check(source: String): String? {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        return try {
            TypeChecker().typeCheck(parser.parseParsedModule(source.trimIndent(), "test.mz"), Paths.get("."))
            null
        } catch (e: Throwable) {
            e.message ?: e::class.simpleName ?: "rejected"
        }
    }

    @Test
    fun `an open parameter accepts an extra field`() {
        val msg = check(
            """
            let my_func: {x: Int, y: Int, ...} -> Int = {x, y} -> x + y
            my_func {x = 1, y = 0, z = ""}
        """
        )
        if (msg != null) fail("an open parameter rejected an extra field `z`: $msg")
    }

    @Test
    fun `a closed parameter rejects the same extra field`() {
        // The control. Without it the test above passes just as well against a
        // typechecker that accepts everything.
        val msg = check(
            """
            let my_func: {x: Int, y: Int} -> Int = {x, y} -> x + y
            my_func {x = 1, y = 0, z = ""}
        """
        )
        assertTrue(msg != null, "a CLOSED parameter accepted an extra field `z`")
        assertTrue(msg!!.contains("z"), "the rejection should name the offending field `z`: $msg")
    }

    @Test
    fun `omitting a named field is a PARTIAL APPLICATION, not a missing argument`() {
        // Not an error, and worth pinning because it looks like one. Arguments are
        // record fields, so supplying a subset subtracts those fields from the
        // parameter row and returns a function wanting the rest. `my_func {x = 1}`
        // is therefore `{ y: Int } -> Int`, and it is only an error if the result
        // is used as an Int.
        val t = typeOfBinding(
            """
            let my_func: {x: Int, y: Int} -> Int = {x, y} -> x + y
            let r = my_func {x = 1}
            r
        """, "r"
        )
        assertTrue(
            t is MezzeType.FunctionType,
            "omitting `y` should leave a function wanting `y`, got ${t?.let { prettyPrintType(it) }}"
        )
        val remaining = ((t as MezzeType.FunctionType).paramType as MezzeType.RecordType).fields.keys
        assertEquals(setOf("y"), remaining, "the remaining parameter should be exactly the omitted field")
    }

    @Test
    fun `an unknown field name is still rejected`() {
        // The boundary: subtraction only applies to fields the function DECLARES.
        // A name it does not declare is a mistake, not a partial application.
        val msg = check(
            """
            let my_func: {x: Int, y: Int} -> Int = {x, y} -> x + y
            let r = my_func {q = 1}
            r
        """
        )
        assertTrue(msg != null, "a call naming an undeclared field `q` was accepted")
        assertTrue(msg!!.contains("q"), "the rejection should name `q`: $msg")
    }

    private fun typeOfBinding(source: String, binding: String): MezzeType? {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        val result = TypeChecker().typeCheck(
            parser.parseParsedModule(source.trimIndent(), "test.mz"), Paths.get(".")
        )
        return result.getVariableType(binding)
    }
}
