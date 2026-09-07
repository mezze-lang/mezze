package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Paths

/**
 * Tests for open record type validation.
 *
 * Open records (`{ name: Str, age: Int, ... }`) allow extra fields
 * but should still require all declared fields.
 */
class OpenRecordValidationTest {

    private val parser = MezzeParser()

    private fun shouldPass(source: String) {
        val tc = TypeChecker()
        val parsedModule = parser.parseParsedModule(source.trimIndent(), "test.mz")
        try {
            val result = tc.checkParsedModule(parsedModule, Paths.get("."), currentModulePath = null)
            assertTrue(result is TypeChecker.TypeCheckResult.Success,
                "Expected success but got failure: ${(result as? TypeChecker.TypeCheckResult.Failure)?.errors?.joinToString { it.message }}")
        } catch (e: TypeCheckError) {
            fail("Expected success but got TypeCheckError: ${e.message}")
        }
    }

    private fun shouldFail(source: String, expectedError: String? = null) {
        val tc = TypeChecker()
        val parsedModule = parser.parseParsedModule(source.trimIndent(), "test.mz")
        try {
            val result = tc.checkParsedModule(parsedModule, Paths.get("."), currentModulePath = null)
            if (result is TypeChecker.TypeCheckResult.Failure) {
                if (expectedError != null) {
                    val errors = result.errors
                    assertTrue(errors.any { it.message.contains(expectedError, ignoreCase = true) },
                        "Expected error containing '$expectedError' but got: ${errors.joinToString { it.message }}")
                }
                return // test passed — type-checking failed as expected
            }
            fail("Expected failure but type-checking succeeded")
        } catch (e: TypeCheckError) {
            // Type-check threw — this counts as failure
            if (expectedError != null) {
                assertTrue(e.message?.contains(expectedError, ignoreCase = true) == true,
                    "Expected error containing '$expectedError' but got: ${e.message}")
            }
        }
    }

    // ========================================================================
    // Closed record validation
    // ========================================================================

    @Test
    fun `closed record with all fields succeeds`() {
        shouldPass("""
            type Person = { name: Str, age: Int }
            let person: Person = { name = "Alice", age = 30 }
        """)
    }

    @Test
    fun `closed record with extra field fails`() {
        shouldFail("""
            type Person = { name: Str, age: Int }
            let person: Person = { name = "Alice", age = 30, city = "NYC" }
        """, "extra")
    }

    @Test
    fun `closed record with missing field fails`() {
        shouldFail("""
            type Person = { name: Str, age: Int }
            let person: Person = { name = "Alice" }
        """, "missing")
    }

    // ========================================================================
    // Open record validation (the bug fix)
    // ========================================================================

    @Test
    fun `open record with all fields succeeds`() {
        shouldPass("""
            type Person = { name: Str, age: Int, ... }
            let person: Person = { name = "Alice", age = 30 }
        """)
    }

    @Test
    fun `open record with extra fields succeeds`() {
        shouldPass("""
            type Person = { name: Str, age: Int, ... }
            let person: Person = { name = "Alice", age = 30, city = "NYC" }
        """)
    }

    @Test
    fun `open record with missing declared field fails`() {
        shouldFail("""
            type Person = { name: Str, age: Int, ... }
            let person: Person = { name = "Alice", city = "NYC" }
        """, "missing")
    }

    @Test
    fun `open record with all declared fields missing fails`() {
        shouldFail("""
            type Person = { name: Str, age: Int, ... }
            let person: Person = { city = "NYC", country = "NL" }
        """, "missing")
    }

    @Test
    fun `open record with extra fields and all required fields succeeds`() {
        shouldPass("""
            type Config = { host: Str, port: Int, ... }
            let config: Config = { host = "localhost", port = 8080, debug = True, timeout = 30 }
        """)
    }

    // ========================================================================
    // Nested / argument-passing open records (unifyRecords open branch).
    //
    // The soundness hole: an open record `{ a: Int, b: Int, ... }` used as a
    // parameter/nested type still REQUIRES both `a` AND `b`. The `...` only
    // admits EXTRA fields beyond the declared ones — it does NOT make the
    // declared fields optional. This is checked through `unifyRecords`' open
    // branch (arg unification), a different path than the top-level annotation
    // check above, so it gets its own coverage.
    // ========================================================================

    @Test
    fun `nested open record arg requires all declared fields — missing one fails`() {
        // f: { r: { a: Int, b: Int, ... } }  — passing { r = { a = 1 } } drops b.
        shouldFail("""
            let f = { r: { a: Int, b: Int, ... } } -> r.a
            let g = {} -> f { r = { a = 1 } }
        """, "b")
    }

    @Test
    fun `nested open record arg with both declared fields succeeds`() {
        shouldPass("""
            let f = { r: { a: Int, b: Int, ... } } -> r.a
            let g = {} -> f { r = { a = 1, b = 2 } }
        """)
    }

    @Test
    fun `nested open record arg with both fields plus extras succeeds`() {
        // `...` admits the extra `c` — but a and b were still both required.
        shouldPass("""
            let f = { r: { a: Int, b: Int, ... } } -> r.a
            let g = {} -> f { r = { a = 1, b = 2, c = 3 } }
        """)
    }

    @Test
    fun `open record arg missing a required field fails (flat)`() {
        // The flat version of the same rule, straight through arg unification.
        shouldFail("""
            let f = { user: { name: Str, age: Str, ... } } -> user.name
            let g = {} -> f { user = { name = "Alice" } }
        """, "age")
    }

    @Test
    fun `open record arg with all required fields plus extras succeeds (flat)`() {
        shouldPass("""
            let f = { user: { name: Str, age: Str, ... } } -> user.name
            let g = {} -> f { user = { name = "Alice", age = "30", city = "NYC" } }
        """)
    }

    // ========================================================================
    // #11 SOUNDNESS (DEFERRED — see @Disabled note): an OPEN value `{ x: Int, ... }`
    // must NOT satisfy a demand for a DIFFERENT declared field `{ y: Int, ... }`.
    // The open∥open unification branch silently absorbs the demanded `y` into the
    // value's row var → runtime `Field not found: y`. NOT fixed: unifyRecords is
    // SYMMETRIC and cannot distinguish this launder from the legitimate case of a
    // value carrying extra fields beyond an open demand (`{ x, y, ... }` vs
    // `{ y, ... }`) — both are "field on one open side, absent from the other".
    // A correct fix needs expected-vs-actual (subsumption) direction threaded into
    // record unification or applied at the arg-check call site.
    // ========================================================================

    @org.junit.jupiter.api.Disabled("Known: open∥open record unification launders a demanded field; needs directional subsumption (see comment)")
    @Test
    fun `open value does not satisfy demand for a field it does not declare`() {
        shouldFail("""
            let readY : { r: { y: Int, ... } } -> Int = { r } -> r.y
            let f = { v: { x: Int, ... } } -> readY { r = v }
        """, "y")
    }

    @Test
    fun `open value with the demanded field plus extras still succeeds`() {
        // Control: the value DOES declare y (plus extra x) — must still pass.
        // (This is exactly the legitimate case a symmetric strict rule would break,
        // which is why #11 is deferred to a directional fix.)
        shouldPass("""
            let readY : { r: { y: Int, ... } } -> Int = { r } -> r.y
            let f = { v: { x: Int, y: Int, ... } } -> readY { r = v }
        """)
    }
}
