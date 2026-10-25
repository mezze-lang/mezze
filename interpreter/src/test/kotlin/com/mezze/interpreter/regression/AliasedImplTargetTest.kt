package com.mezze.interpreter.regression

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * An impl whose target arrived under a `use … as` alias.
 *
 * An impl's target was carried as the SPELLING it was written with, and a
 * rename makes that spelling local. `impl Describe for MyErr` registered
 * "MyErr"; a value of the same declaration arrived as `DecodeError`; nothing
 * matched them. The diagnostic even listed the impl it would not use:
 *
 *     Thing does not implement 'Describe'.
 *       Available impls: impl Describe for Widget
 *
 * One declaration has one content hash however it is spelled, so the fix is to
 * resolve the target where it MEANS something — in the module that wrote the
 * impl — and carry that. `IdentityGroup` already did exactly this for the effect
 * rows on a type declaration (`stampRows`), and this is its sibling.
 *
 * Four places had to agree, each of which had its own name-based answer:
 * `AbilityImplFactory` (reads what the declaration carries), `AbilityChecker`
 * (asks identities before annotations), `TypeValidator`'s match ladder, and
 * `MonoTypeOps.typeMatchesTarget` (which decides the actual dispatch, and whose
 * failure showed up only at runtime as `Field not found: describe`).
 *
 * This is not a nominal loosening. Distinctness still rejects: the
 * TypeApplication arm of `typeMatchesTarget` continues to veto a pair whose
 * hashes DIFFER. Only equal hashes — the same declaration — newly match.
 */
class AliasedImplTargetTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    /**
     * THE regression. The impl names the alias; the value arrives as the
     * declaration's real type, from `body_json`'s own signature.
     */
    @Test
    fun `an impl written against an aliased import matches the real type`() {
        val src = """
            use std::codec::core::{ DecodeError as MyErr }
            use std::effects::http::{ body_json, response_ok_text }

            ability Describe for 'a where { describe: { it: 'a } -> Str }

            impl Describe for MyErr where { describe = { it } -> "described" }

            match body_json { resp = response_ok_text { body = "not json" } } is
              Ok { val = _ } -> "unexpectedly ok"
              Err { error = e } -> e.describe {}
        """.trimIndent()
        assertEquals("described", eval(src))
    }

    /** The control: the same impl written against the name as imported. */
    @Test
    fun `an impl written against the imported name still matches`() {
        val src = """
            use std::codec::core::{ DecodeError }
            use std::effects::http::{ body_json, response_ok_text }

            ability Describe for 'a where { describe: { it: 'a } -> Str }

            impl Describe for DecodeError where { describe = { it } -> "described" }

            match body_json { resp = response_ok_text { body = "not json" } } is
              Ok { val = _ } -> "unexpectedly ok"
              Err { error = e } -> e.describe {}
        """.trimIndent()
        assertEquals("described", eval(src))
    }

    /**
     * The alias is usable as a TYPE as well, not only as an impl target — so
     * the rename is a spelling throughout, and annotating with it agrees with
     * the value the stdlib produced.
     */
    @Test
    fun `the alias also annotates a binding of that declaration`() {
        val src = """
            use std::codec::core::{ DecodeError as MyErr }
            use std::effects::http::{ body_json, response_ok_text }

            ability Describe for 'a where { describe: { it: 'a } -> Str }

            impl Describe for MyErr where { describe = { it } -> it.message }

            match body_json { resp = response_ok_text { body = "not json" } } is
              Ok { val = _ } -> "unexpectedly ok"
              Err { error = e } -> do
                let named: MyErr = e
                named.describe {}
        """.trimIndent()
        assertEquals(true, (eval(src) as String).isNotEmpty())
    }
}
