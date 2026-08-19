package com.mezze.language.typechecker
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A USER-WRITTEN open record (`{ a: Int, ... }`) must NOT let you read an unlisted
 * field. The `...` means "the caller may pass extra fields I don't know about" — it
 * does not license reading an arbitrary field here. Found by the multi-agent
 * soundness hunt: `{ r: { a: Int, ... } } -> r.zzz` type-checked and INVENTED
 * `zzz`, so the caller could omit it and `r.zzz` read a nonexistent field at
 * runtime (worse: `(r.go){}` called a phantom function). The invention path is
 * kept ONLY for solver-inferred rows (an unannotated `{ r } -> r.a`, where growing
 * the param's shape is legitimate inference) — distinguished by the `userOpen_` row.
 */
class OpenRecordFieldInventionTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker
    @BeforeEach fun s() { ModuleResolver.clearCache(); TypeChecker.clearCache(); parser = MezzeParser(); tc = TypeChecker() }
    private fun err(src: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    @Test fun `reading an unlisted field on a user open record is rejected`() {
        val e = err("let getField = { r: { a: Int, ... } } -> r.zzz")
        assertNotNull(e, "reading unlisted `zzz` on `{ a: Int, ... }` must be rejected")
        assertTrue(e!!.contains("zzz"), "error names the missing field: $e")
    }

    @Test fun `calling an unlisted field as a function on a user open record is rejected`() {
        assertNotNull(err("let run = { r: { a: Int, ... } } -> (r.go) {}"),
            "a phantom field called as a function must be rejected")
    }

    @Test fun `reading a LISTED field on a user open record is accepted`() {
        assertNull(err("let getField = { r: { a: Int, ... } } -> r.a"))
    }

    @Test fun `passing extra fields to a user open-record param is still accepted`() {
        assertNull(err("""
            let f = { r: { a: Int, ... } } -> r.a
            let go = {} -> f { r = { a = 1, b = 2, c = 3 } }
        """), "the whole point of `...` — caller may pass extras")
    }

    @Test fun `an inferred open row still grows for an unannotated param`() {
        assertNull(err("let getField = { r } -> r.a"),
            "unannotated `{ r } -> r.a` legitimately infers `r : { a: 'x, ... }`")
        assertNull(err("let f = { r } -> r.a + r.b"),
            "multi-field access on an unannotated param grows the inferred row")
    }

    @Test fun `a closed record still rejects unlisted fields`() {
        assertNotNull(err("let f = { r: { a: Int } } -> r.zzz"))
    }
}
