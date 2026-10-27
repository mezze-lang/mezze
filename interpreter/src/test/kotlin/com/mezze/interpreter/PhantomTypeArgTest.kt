package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A phantom type argument is enforced on the NOMINAL forms (BUG 33).
 *
 * `type Tag 'a = | Tagged { n: Int }, |` declares `'a` and no payload mentions
 * it, so the only thing carrying it is the applied type's `typeArgs`.
 * `unifyVariants` never read those — `typeArgs` was added so the PRINTER could
 * render `Tree Int` (#194) and was never wired into identity, which rested on
 * the declaration's content hash alone, and `Tag Str` and `Tag Int` share that.
 * So the two were mutually assignable: across an annotation, a function boundary
 * and a return. Where a param IS used the payloads differ and body unification
 * catches it, which is why this never showed.
 *
 * `newtype` always got it right, because a newtype reaches unification as a
 * `TypeApplication` whose `args` ARE compared. The stdlib depends on that:
 * `newtype IterCursor 'a = __NativeIterCursor` is an opaque native handle tagged
 * by its element type — exactly what Rust needs `PhantomData` for — and
 * retagging an `IterCursor Int` as `IterCursor Str` has always been rejected.
 * So Mezze already HAD working phantom parameters; the variant form dropped them.
 *
 * Fixed by unifying a variant's `typeArgs` pairwise, the way
 * `unifyTypeApplications` already does for the same reason.
 *
 * Rust's OTHER answer was tried and is wrong here. E0392 ("parameter `T` is
 * never used", with "consider … using a marker such as `PhantomData`") refuses
 * the declaration; implemented as a validator, it rejected `IterCursor 'a` —
 * sound, intended, load-bearing code — and the stdlib gate caught it as a false
 * positive. Mezze has no `PhantomData` to opt back in with, so refusing the
 * declaration would remove the capability rather than fix it.
 */
class PhantomTypeArgTest {
    private fun run(src: String): Any = MezzeTestHelper.run(src.trimIndent())

    private fun refused(src: String): String {
        val outcome = runCatching { run(src) }
        assert(outcome.isFailure) { "expected a type error, got ${outcome.getOrNull()}" }
        return outcome.exceptionOrNull()?.message ?: ""
    }

    /** A variant's phantom arg survives a `let` annotation. */
    @Test fun `a variant's phantom arg is enforced across an annotation`() {
        val message = refused(
            """
            type Tag 'a = | Tagged { n: Int }, |
            let s: Tag Str = Tagged { n = 1 }
            let t: Tag Int = s
            match t is
              Tagged { n } -> n
            """
        )
        assert(message.contains("Type annotation mismatch")) {
            "expected an annotation mismatch; got: $message"
        }
    }

    /** And a function boundary. */
    @Test fun `a variant's phantom arg is enforced across a function boundary`() {
        val message = refused(
            """
            type Tag 'a = | Tagged { n: Int }, |
            let want_int: { t: Tag Int } -> Int = { t } -> match t is
              Tagged { n } -> n
            let s: Tag Str = Tagged { n = 1 }
            want_int { t = s }
            """
        )
        assert(message.contains("Tag Int")) {
            "expected the mismatch to name `Tag Int`; got: $message"
        }
    }

    /** And a return type. */
    @Test fun `a variant's phantom arg is enforced out of a return`() {
        refused(
            """
            type Tag 'a = | Tagged { n: Int }, |
            let mk: {} -> Tag Str = {} -> Tagged { n = 1 }
            let t: Tag Int = mk {}
            match t is
              Tagged { n } -> n
            """
        )
    }

    /**
     * A `newtype`'s phantom arg is enforced — it always was, and this is the
     * form the stdlib's `IterCursor 'a` relies on. Kept so the variant fix
     * cannot be mistaken for having introduced the capability.
     */
    @Test fun `a newtype's phantom arg is enforced`() {
        refused(
            """
            newtype UTag 'a = Int
            let s: UTag Str = UTag.(1)
            let t: UTag Int = s
            0
            """
        )
    }

    /** The stdlib case itself: an `IterCursor Int` is not an `IterCursor Str`. */
    @Test fun `an IterCursor cannot be retagged`() {
        val message = refused(
            """
            use std::collections::iter
            use std::collections::iter::{ IterCursor }
            let it = iter::range { start = 1, end = 4 }
            let c = iter::cursor { it = it }
            let s: IterCursor Str = c
            match iter::cursor_next { c = s } is
              Some { val } -> val
              None -> "none"
            """
        )
        assert(message.contains("IterCursor")) {
            "expected the mismatch to name IterCursor; got: $message"
        }
    }

    /** A correctly tagged cursor still works — the fix must not cost the feature. */
    @Test fun `a correctly tagged IterCursor still pulls`() {
        assertEquals(
            1L,
            run(
                """
                use std::collections::iter
                use std::collections::iter::{ IterCursor }
                let it = iter::range { start = 1, end = 4 }
                let c: IterCursor Int = iter::cursor { it = it }
                match iter::cursor_next { c = c } is
                  Some { val } -> val
                  None -> 0
                """
            ),
        )
    }

    /**
     * A tag cannot be LAUNDERED — this is what makes the enforcement real
     * rather than cosmetic.
     *
     * `{ x: UTag 'a } -> UTag 'b` would retag anything as anything, and it is
     * refused because `'b` appears in no parameter. Without this, every other
     * case here could be walked around in one function.
     */
    @Test fun `a phantom tag cannot be laundered by a retagging function`() {
        refused(
            """
            newtype UTag 'a = Int
            let retag: { x: UTag 'a } -> UTag 'b = { x } -> x
            let s: UTag Str = UTag.(1)
            let i: UTag Int = retag { x = s }
            0
            """
        )
    }

    /**
     * The intended use: two brands over one payload do not mix.
     *
     * This is what `newtype Tag 'a = …` is FOR — `Id User` against `Id Order`,
     * units, a handle tagged by its element type — and it is why refusing the
     * declaration (Rust's E0392) was the wrong fix: it would remove this.
     *
     * Building a FRESH tagged value through a tag-polymorphic constructor
     * (`{ n: Int } -> Id 'a`) is allowed and is not laundering: it mints a new
     * tag rather than moving an existing one, exactly as `Id::new()` does.
     */
    @Test fun `two brands over the same payload do not mix`() {
        val message = refused(
            """
            newtype Id 'a = Int
            type User = { name: Str }
            type Order = { total: Int }
            let user_id: Id User = Id.(7)
            let want_order: { id: Id Order } -> Int = { id } -> 0
            want_order { id = user_id }
            """
        )
        assert(message.contains("Id Order")) {
            "expected the mismatch to name `Id Order`; got: $message"
        }
    }

    /**
     * A FORGOTTEN type param is caught at the first use, by the payload.
     *
     * `newtype Box 'a = Int` where `= 'a` was meant is the accident Rust's
     * E0392 exists to catch at the declaration. Mezze catches it the moment a
     * value is put in — `expected Int, but got Str` — which is why the
     * declaration does not need refusing, and refusing it would have cost the
     * branding above.
     */
    @Test fun `a forgotten type param is caught at the first use`() {
        val message = refused(
            """
            newtype Box 'a = Int
            let b: Box Str = Box.("hello")
            0
            """
        )
        assert(message.contains("expected Int")) {
            "expected the payload mismatch to name Int; got: $message"
        }
    }

    /**
     * A RECORD's phantom arg is still erased, and that is the structural rule
     * working rather than a leftover.
     *
     * A record alias NAMES A SHAPE, and Mezze compares records structurally, so
     * `type RTag 'a = { n: Int }` really does make both instantiations
     * `{ n: Int }` — there is no nominal wall to enforce. A variant is nominal
     * (`unifyVariants` is built out of identity vetoes and alias walls), which
     * is why dropping ITS args was the inconsistency. Tagged data over one shape
     * is what `newtype` is for, as `IterCursor` shows.
     *
     * Pinned so that if records ever become nominal here, it is deliberate.
     */
    @Test fun `a record's phantom arg is erased, structurally`() {
        assertEquals(
            1L,
            run(
                """
                type RTag 'a = { n: Int }
                let s: RTag Str = { n = 1 }
                let t: RTag Int = s
                t.n
                """
            ),
        )
    }

    /** CONTROL: a param the payload uses was always enforced, by the payloads. */
    @Test fun `a used variant arg is enforced`() {
        val message = refused(
            """
            type Box 'a = | Boxed { val: 'a }, |
            let s: Box Str = Boxed { val = "x" }
            let t: Box Int = s
            match t is
              Boxed { val } -> val
            """
        )
        assert(message.contains("Type annotation mismatch")) {
            "expected an annotation mismatch; got: $message"
        }
    }

    /** CONTROL: and a used record field, structurally. */
    @Test fun `a used record arg is enforced`() {
        val message = refused(
            """
            type Box 'a = { val: 'a }
            let s: Box Str = { val = "x" }
            let t: Box Int = s
            t.val
            """
        )
        assert(message.contains("Type annotation mismatch")) {
            "expected an annotation mismatch; got: $message"
        }
    }

    /**
     * A variant that is generic and USED generically still works — the arg
     * unification must bind `'a`, not merely compare it.
     */
    @Test fun `a generic variant still binds its arg`() {
        assertEquals(
            "x",
            run(
                """
                type Box 'a = | Boxed { val: 'a }, |
                let unwrap: { b: Box 'a } -> 'a = { b } -> match b is
                  Boxed { val } -> val
                unwrap { b = Boxed { val = "x" } }
                """
            ).toString(),
        )
    }
}
