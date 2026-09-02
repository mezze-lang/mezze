package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * The `*` performed-marker (docs/effect-model.md rule 5). A function that performs
 * AND handles effects internally carries `<*>` on its arrow; `*` propagates through
 * calls; a `*`-free arrow certifies purity (no effectful machinery runs).
 *
 * `*` is `EffectRow.performed`. We read it off the arrow directly rather than the
 * printed string so the certificate is exact.
 */
class EffectStarPropagationTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach
    fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun arrowRow(name: String, src: String): MezzeType.EffectRow? {
        val result = tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true)
        val ty = result.getVariableType(name) ?: throw AssertionError("no type for `$name`")
        return (ty as MezzeType.FunctionType).effects
    }

    private val prelude = "use std::effects::exception::{ Exception, ExceptionToResult }"
    private val safeDiv = "let safe_div : { a: Int, b: Int } -> Int <Exception> = { a, b } -> " +
        "if b == 0 then Exception.throw { error = \"x\" } else a / b"

    @Test
    fun `a genuinely pure function is star-free (the purity certificate)`() {
        val row = arrowRow("add", "$prelude\nlet add = { a, b } -> a + b")
        assertTrue(row == null || (!row.performed && row.isPure)) {
            "a pure function must certify pure (no `*`, no effects), got: $row"
        }
    }

    @Test
    fun `a function that performs and handles internally carries the star marker`() {
        val row = arrowRow("run", "$prelude\n$safeDiv\nlet run = {} -> perform ExceptionToResult in safe_div { a = 10, b = 2 }.ok {}")
        assertTrue(row?.performed == true) {
            "a perform-and-handle function must carry `*` (performed) on its arrow, got: $row"
        }
    }

    @Test
    fun `the star marker propagates to a caller of a starred function`() {
        val row = arrowRow(
            "caller",
            "$prelude\n$safeDiv\n" +
                "let run = {} -> perform ExceptionToResult in safe_div { a = 10, b = 2 }.ok {}\n" +
                "let caller = {} -> run {}"
        )
        assertTrue(row?.performed == true) {
            "`*` must propagate to a caller of a `<*>` function, got: $row"
        }
    }

    @Test
    fun `a caller of only pure functions stays star-free`() {
        val row = arrowRow(
            "caller",
            "$prelude\nlet add = { a, b } -> a + b\nlet caller = {} -> add { a = 1, b = 2 }"
        )
        assertFalse(row?.performed == true) {
            "a caller of only pure functions must stay `*`-free, got: $row"
        }
    }
}
