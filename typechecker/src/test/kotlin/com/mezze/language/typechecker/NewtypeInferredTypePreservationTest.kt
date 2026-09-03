package com.mezze.language.typechecker

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import com.mezze.language.langservice.lookup.TypedAstLookup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * #395 — a newtype value's INFERRED/hover type must keep its wrapper in EVERY
 * position a value flows through, not only at top level.
 *
 * The bug: newtype construction `Name.(x)` emits the runtime-ERASED underlying
 * value node (correct for lowering), but that node's STATIC `.type` was the bare
 * underlying (`Float`), so a do-block / record-field / list-element binding that
 * reads the emitted node's type (rather than the constraint generator's
 * authoritative type, as top-level bindings do) reported `Float` instead of the
 * wrapper. That erased type also fed #394's `to_dbg` PrintType, so a do-block
 * `let len = Length.(4.0); len.to_dbg {}` printed `4.0` not `Length.(4.0)`.
 *
 * Fix: emit stamps the solver's per-loc construction result type back onto the
 * erased value node (see TypedAstBuilder ParsedNewtypeConstruct + withType).
 * Runtime lowering is unchanged — the node's shape is identical.
 *
 * The `Length` wrapper renders as `Length` (its TypeApplication identity).
 */
class NewtypeInferredTypePreservationTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach fun setup() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); typeChecker = TypeChecker()
    }

    private val header = "newtype Length = Float\nnewtype Area = Float\n"

    private fun check(body: String): TypeCheckResult {
        val parsed = parser.parseParsedModule(body, "test.mz")
        return typeChecker.typeCheck(parsed, Paths.get("."), enablePrelude = true)
    }

    /** Inferred type of the do-block/lambda binding whose name token is at (line,col). */
    private fun bindingAt(body: String, line: Int, col: Int): String {
        val mod = check(body).typedModule ?: error("no module")
        return when (val res = TypedAstLookup.findAtPosition(mod, line, col)) {
            is TypedAstLookup.LookupResult.Binding -> prettyPrintType(res.type)
            is TypedAstLookup.LookupResult.Expr -> prettyPrintType(res.expr.type)
            TypedAstLookup.LookupResult.NotFound -> "<not-found>"
        }
    }

    private fun topLevel(body: String, name: String): String =
        check(body).getVariableType(name)?.let { prettyPrintType(it) } ?: "<null>"

    // ─── top-level positions (baselines — must already keep the wrapper) ─────

    @Test fun `top-level binding keeps Length`() {
        assertEquals("Length", topLevel(header + "let w = Length.(4.0)\n", "w"))
    }

    @Test fun `top-level record field keeps Length`() {
        assertEquals("{ w: Length }", topLevel(header + "let r = { w = Length.(4.0) }\n", "r"))
    }

    @Test fun `function return keeps Length`() {
        assertEquals("{} -> Length", topLevel(header + "let mk = {} -> Length.(4.0)\n", "mk"))
    }

    @Test fun `top-level list element keeps Length`() {
        assertEquals("[Length]", topLevel(header + "let xs = [Length.(1.0), Length.(2.0)]\n", "xs"))
    }

    // ─── do-block positions (these ERASED before the fix) ────────────────────

    @Test fun `do-block binding keeps Length`() {
        val body = header + "let f = {} -> do\n  let w = Length.(4.0)\n  w\n"
        assertEquals("Length", bindingAt(body, 4, 7))
    }

    @Test fun `do-block annotated binding keeps Length`() {
        val body = header + "let f = {} -> do\n  let w : Length = Length.(4.0)\n  w\n"
        assertEquals("Length", bindingAt(body, 4, 7))
    }

    @Test fun `do-block nested record field keeps Length`() {
        val body = header + "let f = {} -> do\n  let r = { w = Length.(4.0) }\n  r\n"
        assertEquals("{ w: Length }", bindingAt(body, 4, 7))
    }

    @Test fun `do-block nested list element keeps Length`() {
        val body = header + "let f = {} -> do\n  let xs = [Length.(1.0), Length.(2.0)]\n  xs\n"
        assertEquals("[Length]", bindingAt(body, 4, 7))
    }

    // ─── match binding + fn param annotated ──────────────────────────────────

    @Test fun `match-bound newtype value keeps Length`() {
        val body = header +
            "let g = { o } -> match o is\n  Some { val = w } -> w\n  None -> Length.(0.0)\n"
        // the returned `w` (a Length) — cursor on the trailing `w`
        assertEquals("Length", bindingAt(body, 4, 23))
    }

    @Test fun `annotated fn param keeps Length`() {
        val body = header + "let f = { w : Length } -> w\n"
        // lambda type shows the param and result as Length
        val t = bindingAt(body, 3, 11)
        assertTrue(t.contains("Length"), "expected Length in param/lambda type, got: $t")
    }

    // ─── ability-impl `having 'out = Area` REGRESSION GUARD ──────────────────
    // The having-out path must NOT be weakened: `wall * height` (NumMul on
    // Length yielding Area) still infers Area, even inside a do-block. `*` is a
    // method call, not a newtype construction, so the #395 stamp never touches
    // it — this locks that in.

    @Test fun `having-out multiplication still yields Area (top-level)`() {
        val body = """
            use std::abilities::numeric::{NumMul}
            newtype Length = Float
            newtype Area = Float
            impl NumMul Length for Length having 'out = Area where {
              mul = { it, r } -> match it is
                Length.(a) -> match r is
                  Length.(b) -> Area.(a * b)
            }
            let area = Length.(4.0) * Length.(3.0)
        """.trimIndent() + "\n"
        assertEquals("Area", topLevel(body, "area"))
    }

    @Test fun `having-out multiplication still yields Area (do-block)`() {
        val body = """
            use std::abilities::numeric::{NumMul}
            newtype Length = Float
            newtype Area = Float
            impl NumMul Length for Length having 'out = Area where {
              mul = { it, r } -> match it is
                Length.(a) -> match r is
                  Length.(b) -> Area.(a * b)
            }
            let mk = {} -> do
              let wall = Length.(4.0)
              let height = Length.(3.0)
              wall * height
        """.trimIndent() + "\n"
        assertEquals("{} -> Area", topLevel(body, "mk"))
    }
}
