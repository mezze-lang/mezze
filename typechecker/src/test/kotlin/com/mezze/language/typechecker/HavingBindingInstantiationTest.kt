package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertEquals

/**
 * A `having 'out` binding is freshened per call site wherever a call site can see
 * it — and where it cannot, sharing it is not observable.
 *
 * `collectFreeTypeVars` does not collect a constraint's `associatedTypeBindings`,
 * while `occursCheck` and `MezzeType.anyChild` both do. That reads like the
 * soundness hole its own doc warns about — "every call site shares ONE variable
 * and the first caller to pin it pins it for the rest" — and it is not one. The
 * two cases are exhaustive and neither is the feared one:
 *
 *  - `out` OCCURS elsewhere in the type. `{n, acc} -> acc + n` RETURNS it, so it
 *    is already collected through `returnType` and already quantified. That is
 *    the case these tests exercise, at two different instantiations in one
 *    program.
 *
 *  - `out` occurs nowhere else — a recursive `sum_to` whose operands solve to
 *    Int, where the return is the if/else join rather than the `+` result. Then
 *    no caller can observe it: it is absent from the parameters, the return, the
 *    constraint's type arguments and the effect row, which is everything a call
 *    site sees.
 *
 * Collecting them unconditionally was TRIED, and is worse than inert: it mints a
 * variable no call site can pin, the obligation resolves against nothing, and
 * `LazyEffectsTest.test suspended value in recursive function` returns 15 where
 * it must return an `Err`. That experiment is why this file asserts the property
 * rather than the implementation.
 */
class HavingBindingInstantiationTest {

    private lateinit var parser: MezzeParser

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
    }

    private fun typeOf(source: String, binding: String): MezzeType? {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        return TypeChecker()
            .typeCheck(parser.parseParsedModule(source.trimIndent(), "having.mz"), Paths.get("."))
            .getVariableType(binding)
    }

    @Test
    fun `one polymorphic binding instantiates at Int AND at Float in the same program`() {
        // THE soundness question, stated as a program. If `out` were shared across
        // call sites, the first use would pin it and the second would either be
        // rejected or come back with the first one's type.
        val src = """
            let add_up = {n, acc} -> acc + n
            let i = add_up {n = 1, acc = 2}
            let f = add_up {n = 1.5, acc = 2.5}
            i
        """

        assertEquals(MezzeType.IntType, typeOf(src, "i"), "the Int call site must stay Int")
        assertEquals(MezzeType.FloatType, typeOf(src, "f"), "the Float call site must stay Float")
    }

    @Test
    fun `the order of the two call sites does not decide the answer`() {
        // The mirror. If one pinned the other, swapping which comes first would
        // change which one is wrong — so a single ordering could pass by luck.
        val src = """
            let add_up = {n, acc} -> acc + n
            let f = add_up {n = 1.5, acc = 2.5}
            let i = add_up {n = 1, acc = 2}
            i
        """

        assertEquals(MezzeType.FloatType, typeOf(src, "f"))
        assertEquals(MezzeType.IntType, typeOf(src, "i"))
    }

    @Test
    fun `an UNANNOTATED recursive function keeps a usable result type`() {
        // The case quantifying the binding broke. `sum_to`'s `out` belongs to a
        // NumAdd whose operands solve to Int, and its return is the if/else join
        // rather than the `+` result — so `out` is in the binding and nowhere
        // else. Quantifying it left `sum` a bare var and the enclosing result
        // collapsed.
        val t = typeOf(
            """
            let sum_to = {n, acc} ->
                if n <= 0 then acc
                else sum_to {n = n - 1, acc = acc + n}
            let total = sum_to {n = 5, acc = 0}
            total
            """,
            "total",
        )
        assertEquals(MezzeType.IntType, t, "a recursive arithmetic fold must produce Int, not an open var")
    }

    @Test
    fun `the same recursive function ANNOTATED agrees`() {
        // The control for the one above: the annotated form always worked, so if
        // the unannotated one disagreed with it that would be the bug showing.
        val t = typeOf(
            """
            let sum_to: {n: Int, acc: Int} -> Int = {n, acc} ->
                if n <= 0 then acc
                else sum_to {n = n - 1, acc = acc + n}
            let total = sum_to {n = 5, acc = 0}
            total
            """,
            "total",
        )
        assertEquals(MezzeType.IntType, t)
    }
}
