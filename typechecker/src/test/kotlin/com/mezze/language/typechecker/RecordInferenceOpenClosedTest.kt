package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Open vs closed records in inferred signatures (the single close-seam:
 * `closeGeneratedRowVars`, routed through every finalization boundary).
 *
 * The rule, stated once and applied everywhere a type is STORED or DISPLAYED:
 *  - a record shape the solver INVENTED (grown by field access, a decode
 *    target) closes to exactly its known fields — `{ a: T }`, no `…`;
 *  - a record the USER wrote open (`{ a: T, … }`) stays open;
 *  - a record the USER wrote closed stays closed.
 *
 * So signatures, hover, and errors all read the exact record, never `{ …, … }`,
 * from one place — no per-site patching. (An inferred codec TARGET is the one
 * value type that stays a bare var for codegen; its shape still prints closed on
 * the constraint arg — see project_multi_decode_target_dispatch.)
 */
class RecordInferenceOpenClosedTest {

    private fun signatureOf(src: String, binding: String): String {
        TypeChecker.clearCache()
        val parsed = MezzeParser().parseParsedModule(src, "t.mz")
        val r = TypeChecker().checkParsedModule(parsed, Paths.get("."), null, true)
        val success = r as? TypeChecker.TypeCheckResult.Success
            ?: error("expected the program to typecheck, got: $r")
        val decl = success.typedModule!!.decls
            .filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
            .find { it.name == binding }
            ?: error("no binding named $binding")
        return com.mezze.language.ast.prettyPrintType(decl.scheme.type)
    }

    @Test fun `a field access infers a CLOSED record`() {
        assertEquals("{ r: { name: 'a } } -> 'a",
            signatureOf("let get_name = { r } -> r.name", "get_name"))
    }

    @Test fun `a nested field access infers CLOSED records at every level`() {
        assertEquals("{ r: { a: { b: 'a } } } -> 'a",
            signatureOf("let deep = { r } -> r.a.b", "deep"))
    }

    @Test fun `multiple field accesses infer one CLOSED record with all fields`() {
        // both fields present; the row is closed (no trailing ...)
        val sig = signatureOf(
            "let both = { r } -> \"\$(r.x.to_str {})\$(r.y)\"", "both")
        assertTrue(sig.contains("x:") && sig.contains("y:"), sig)
        assertTrue(!sig.contains("..."), "inferred record is closed: $sig")
    }

    @Test fun `a user-written open record stays OPEN`() {
        val sig = signatureOf(
            "let f = { r: { name: Str, ... } } -> r.name", "f")
        assertTrue(sig.contains("..."), "user's open row is preserved: $sig")
    }

    @Test fun `a user-written closed record stays CLOSED`() {
        assertEquals("{ r: { name: Str } } -> Str",
            signatureOf("let f = { r: { name: Str } } -> r.name", "f"))
    }

    @Test fun `a from_json decode target prints CLOSED on its constraint`() {
        // The target is a bare var on the VALUE type (codegen needs that), but
        // its inferred shape prints closed on the FromJson constraint arg.
        val sig = signatureOf(
            """
            use std::codec::json::{ FromJson }
            let name_of = { body } -> body
              .from_json {}
              .map { f = { val } -> val.product.product_name }
              .get_or { def = "?" }
            """.trimIndent(),
            "name_of")
        assertTrue(sig.contains("FromJson"), sig)
        assertTrue(sig.contains("product") && sig.contains("product_name"), sig)
        assertTrue(!sig.contains("..."), "decode target shape prints closed: $sig")
    }

    @Test fun `a destructured field not further accessed stays a plain var, closed`() {
        // `{ x }` destructures a record with field `x`; x is used as-is (not
        // field-accessed), so its type is a plain var and the record is closed.
        assertEquals("{ x: 'a } -> 'a", signatureOf("let id = { x } -> x", "id"))
    }
}
