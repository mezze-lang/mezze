package com.mezze.language.langservice.hover

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Paths

/**
 * Hovering an inferred-shape value inside a `from_json` decode chain.
 *
 * The decode target is an OPEN record grown from the fields the code reads:
 * `val.product.product_name` makes the target
 * `{ product: { product_name: Str, … }, … }`. The record NODE'S own solved
 * type stays an unresolved row var (the shape lives in the field accesses that
 * read it, not on the node), so a naive hover printed a bare `'a` for `val`
 * and `val.product` while only the leaf `.product_name` showed `Str`.
 *
 * Hover rebuilds the record from those accesses, so every level of the chain
 * reads as the CLOSED record it actually is (the exact fields read) — the whole
 * point of the lesson. The stored type can't carry this (a concrete decode
 * target breaks codec derivation + codegen), so the rebuild is display-only.
 */
class InferredRecordHoverTest {

    private val src = """
        use std::codec::json::{ FromJson }
        let name_of = { body } -> body.from_json {}
          .to_option {}
          .map { f = { val } -> val.product.product_name }
          .get_or { def = "x" }
    """.trimIndent()

    private fun hover(needle: String): String? {
        val parser = MezzeParser()
        val module = parser.parseParsedModule(src, "t.mz")
        val tc = TypeChecker()
        val result = tc.typeCheck(module, Paths.get("."))
        val ctx = HoverInfo.Context(
            content = src, typeCheckResult = result,
            typeAliases = tc.getTypeAliases(), parsedModule = module,
        )
        val lines = src.lines()
        val li = lines.indexOfFirst { it.contains(".map { f = { val } ->") }
        return HoverInfo.at(ctx, li, lines[li].indexOf(needle))
    }

    @Test
    fun `the record binding shows its full nested shape`() {
        val h = hover("val.product")
        assertNotNull(h, "expected a hover for `val`")
        assertTrue(
            h!!.contains("product") && h.contains("product_name") && h.contains("Str"),
            "hover for `val` should rebuild the nested decode shape, not print a bare var:\n$h",
        )
        // The exact fields the program reads: a projecting decoder produces
        // precisely these and drops the rest of the reply, so the type is CLOSED.
        assertTrue(!h.contains("..."), "the inferred record is closed (exact fields):\n$h")
    }

    @Test
    fun `an intermediate access shows the sub-record`() {
        val h = hover("product.product_name")
        assertNotNull(h, "expected a hover for `val.product`")
        assertTrue(
            h!!.contains("product_name") && h.contains("Str"),
            "hover for `val.product` should show `{ product_name: Str, ... }`:\n$h",
        )
    }

    @Test
    fun `the leaf field keeps its concrete scalar type`() {
        val h = hover("product_name }")
        assertNotNull(h)
        assertTrue(h!!.contains("Str"), "the leaf `.product_name` is `Str`:\n$h")
    }
}

/**
 * The same closed-shape rebuild, but for an INTERMEDIATE binder in a MATCH
 * destructure rather than a `.map` callback: `Ok { val = { product = { product_name } } }`.
 *
 * Hovering `val` or `product` here used to show the innermost leaf's type
 * (`Str`) — the leaf binding's loc spans the whole nested pattern and won.
 * Now the narrow field binder wins first and rebuilds its record from the
 * nested pattern + the leaves' concrete binding types, so each level reads as
 * the closed record it destructures. The scrutinee's own field types are bare
 * vars (a decode target stays a var), so the concrete leaf (`product_name: Str`)
 * comes from `case.bindings`, not the scrutinee.
 */
class MatchDestructureHoverTest {

    private val src = """
        use std::codec::json::{ FromJson }
        let name_of = { body } -> match body.from_json {} is
          Ok { val = { product = { product_name } } } -> product_name
          Err { error = _ } -> "?"
    """.trimIndent()

    private fun hover(needle: String): String? {
        val parser = MezzeParser()
        val module = parser.parseParsedModule(src, "t.mz")
        val tc = TypeChecker()
        val result = tc.typeCheck(module, Paths.get("."))
        val ctx = HoverInfo.Context(
            content = src, typeCheckResult = result,
            typeAliases = tc.getTypeAliases(), parsedModule = module,
        )
        val lines = src.lines()
        val li = lines.indexOfFirst { it.contains("Ok { val") }
        return HoverInfo.at(ctx, li, lines[li].indexOf(needle))
    }

    @Test
    fun `the outer binder shows the full nested closed record`() {
        val h = hover("val =")
        assertNotNull(h, "expected a hover for `val`")
        assertTrue(
            h!!.contains("product") && h.contains("product_name") && h.contains("Str"),
            "hover for `val` should rebuild `{ product: { product_name: Str } }`, not `Str`:\n$h",
        )
        assertTrue(!h.contains("'"), "leaves are concrete, no bare vars:\n$h")
        assertTrue(!h.contains("..."), "the destructured record is closed:\n$h")
    }

    @Test
    fun `the middle binder shows its sub-record`() {
        val h = hover("product = {")
        assertNotNull(h, "expected a hover for `product`")
        assertTrue(
            h!!.contains("product_name") && h.contains("Str") && !h.contains("'"),
            "hover for `product` should show `{ product_name: Str }`:\n$h",
        )
    }

    @Test
    fun `the leaf binder shows its concrete scalar`() {
        val h = hover("product_name } }")
        assertNotNull(h)
        assertTrue(h!!.contains("Str") && !h.contains("'"), "the leaf is `Str`:\n$h")
    }
}
