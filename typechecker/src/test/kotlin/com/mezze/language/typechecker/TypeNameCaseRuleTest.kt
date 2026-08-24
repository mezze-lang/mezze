package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.parser.ParseException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertTrue

/**
 * Pins the language rule: every name in the TYPE namespace must start with
 * an uppercase letter. That covers:
 *   - `type Foo = ...` (records + variants + aliases)
 *   - `newtype Foo = ...` + its constructor
 *   - `ability Foo for 'a where { ... }`
 *   - `effect Foo where { ... }`
 *   - variant constructor names inside `| ... |`
 *
 * The corollary is that a lowercase LHS on `.(` (e.g. `x.("hi")`) is not a
 * type at all — the typechecker returns a rule-specific hint rather than
 * "Unknown type 'x'".
 */
class TypeNameCaseRuleTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun setUp() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun err(src: String): String? = try {
        val p = parser.parseParsedModule(src.trimIndent(), "case.mz")
        tc.typeCheck(p, Paths.get("."), enablePrelude = true)
        null
    } catch (e: ParseException) { e.message ?: "ParseException" }
      catch (e: TypeCheckError) { e.message ?: "TypeCheckError" }
      catch (e: Exception) { e.message ?: e::class.simpleName ?: "Exception" }

    private fun reject(label: String, src: String, mustContain: String) {
        val e = err(src)
        assertTrue(e != null, "[$label] expected error containing '$mustContain', got success")
        assertTrue(e.contains(mustContain), "[$label] error mismatch. want: '$mustContain'\n got: $e")
    }

    private fun ok(label: String, src: String) {
        val e = err(src)
        assertTrue(e == null, "[$label] expected success, got: $e")
    }

    @Test fun `newtype decl name must be uppercase`() {
        reject("newtype.lower", "newtype foo = Int", "must start with an uppercase letter")
    }

    @Test fun `type decl name must be uppercase (variant form)`() {
        reject("type.lower.variant", "type foo = | Red, Blue |", "must start with an uppercase letter")
    }

    @Test fun `type decl name must be uppercase (record form)`() {
        reject("type.lower.record", "type foo = { x: Int }", "must start with an uppercase letter")
    }

    @Test fun `ability decl name must be uppercase`() {
        reject("ability.lower", """
            ability foo for 'a where {
              m: { it: 'a } -> Int,
            }
        """, "Ability 'foo' must start with an uppercase")
    }

    @Test fun `effect decl name must be uppercase`() {
        reject("effect.lower", """
            effect foo where {
              op: {} -> Int,
            }
        """, "Effect 'foo' must start with an uppercase")
    }

    @Test fun `variant constructor must be uppercase in a variant type decl`() {
        reject("variant-ctor.lower", "type T = | someArm |",
            "'someArm' must start with an uppercase")
    }

    @Test fun `variant constructor must be uppercase when mixed with uppercase peers`() {
        reject("variant-ctor.lower-mixed", "type Col = | Red, blue, Green |",
            "'blue' must start with an uppercase")
    }

    // ── ACCEPTED CASES ─────────────────────────────────────────────────

    @Test fun `newtype uppercase decl OK`() { ok("newtype.up", "newtype Age = Int") }
    @Test fun `type uppercase record OK`() { ok("type.record.up", "type P = { x: Int }") }
    @Test fun `type uppercase variant OK`() { ok("type.variant.up", "type Col = | Red, Blue |") }
    @Test fun `ability uppercase decl OK`() {
        ok("ability.up", """
            ability Named for 'a where {
              m: { it: 'a } -> Int,
            }
        """)
    }
    @Test fun `effect uppercase decl OK`() {
        ok("effect.up", """
            effect Log where {
              op: {} -> Int,
            }
        """)
    }

    // ── COROLLARY: `x.("hi")` (lowercase LHS) gives a rule-specific hint ──

    @Test fun `dot-parens with lowercase LHS gives case-rule hint`() {
        val e = err("""
            pub let x = 42
            pub let y = x.("hi")
        """)
        assertTrue(e != null, "expected rejection")
        assertTrue(
            e.contains("uppercase") || e.contains("not a type"),
            "expected case-rule hint, got: $e",
        )
    }

    @Test fun `dot-parens with uppercase-but-unknown LHS gives import hint`() {
        // Uppercase → still routed to "Unknown type". The rule check is
        // strictly for lowercase-first names.
        val e = err("pub let y = { } -> DoesNotExist.(42)")
        assertTrue(e != null, "expected rejection")
        assertTrue(
            e.contains("Unknown type 'DoesNotExist'") || e.contains("import"),
            "expected import hint, got: $e",
        )
    }
}
