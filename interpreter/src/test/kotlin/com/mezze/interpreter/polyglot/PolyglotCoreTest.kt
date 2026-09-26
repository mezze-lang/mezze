package com.mezze.interpreter.polyglot

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.values.MezzeForeign
import com.mezze.interpreter.values.MezzeVariant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Integration tests for std::polyglot::core + std::polyglot::jvm.
 *
 * Every JVM foreign flows through `JvmForeign` — the language-tagged
 * newtype defined in std::polyglot::jvm. Assertions use tag-based
 * checks (isOk/isErr, getPayloadFieldByName) rather than constructor
 * name lookups: variant tags are canonical across the codebase, but
 * name-registration in TagRegistry.getGlobal() is lazy and depends
 * on parse-order.
 *
 * MezzeTestHelper builds a Context with allowAllAccess(true), so JVM
 * host class lookup is enabled out of the box. There is no user-facing
 * Context lifecycle — `impl Jvm for JvmHost` has zero fields and the
 * Context is ambient.
 */
class PolyglotCoreTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    private fun assertOk(result: Any): MezzeVariant {
        assertTrue(result is MezzeVariant, "Expected MezzeVariant, got $result (${result.javaClass})")
        val v = result as MezzeVariant
        assertTrue(v.isOk, "Expected Ok variant, got tag=${v.tag}")
        return v
    }

    private fun assertErr(result: Any): MezzeVariant {
        assertTrue(result is MezzeVariant, "Expected MezzeVariant, got $result (${result.javaClass})")
        val v = result as MezzeVariant
        assertTrue(v.isErr, "Expected Err variant, got tag=${v.tag}")
        return v
    }

    // ==================== Primitive round-trips ====================

    @Test
    fun `JvmForeign from_int round-trips through as_int`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_int }
            let f = from_int { val = 42 }
            f.int{}
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(42L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `JvmForeign from_float round-trips through as_float`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_float }
            let f = from_float { val = 3.14 }
            f.float{}
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(3.14, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `JvmForeign from_str round-trips through as_str`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_str }
            let f = from_str { val = "hello" }
            f.str{}
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals("hello", v.getPayloadFieldByName("val"))
    }

    @Test
    fun `JvmForeign from_bool round-trips through as_bool`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_bool }
            let f = from_bool { val = True }
            f.bool{}
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(true, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `null_val is_null returns True`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ null_val }
            let f = null_val {}
            f.is_null{}
            """.trimIndent()
        )
        assertEquals(true, result)
    }

    @Test
    fun `non-null JvmForeign is_null returns False`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_int }
            let f = from_int { val = 7 }
            f.is_null{}
            """.trimIndent()
        )
        assertEquals(false, result)
    }

    // ==================== Error variants ====================

    @Test
    fun `as_int on string yields Err`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_str }
            let f = from_str { val = "not-a-number" }
            f.int{}
            """.trimIndent()
        )
        assertErr(result)
    }

    @Test
    fun `get_member on non-record returns Err`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_int }
            let f = from_int { val = 5 }
            f.get { name = "does_not_exist" }
            """.trimIndent()
        )
        assertErr(result)
    }

    // ==================== JVM host access ====================

    @Test
    fun `jvm class lookup for ArrayList returns Ok JvmForeign`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            jvm::class { name = "java.util.ArrayList" }
            """.trimIndent()
        )
        val v = assertOk(result)
        val foreign = v.getPayloadFieldByName("val")
        assertTrue(foreign is MezzeForeign, "Expected MezzeForeign, got ${foreign?.javaClass}")
    }

    @Test
    fun `jvm class lookup for nonexistent class returns Err`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            jvm::class { name = "com.does.not.Exist_XYZ" }
            """.trimIndent()
        )
        assertErr(result)
    }

    @Test
    fun `jvm ArrayList new returns instance`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            match jvm::class { name = "java.util.ArrayList" } is
              Ok { val = klass } -> klass.new { args = [] }
              Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        val instance = v.getPayloadFieldByName("val")
        assertTrue(instance is MezzeForeign)
    }

    @Test
    fun `jvm ArrayList empty size is 0`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            match jvm::class { name = "java.util.ArrayList" } is
              Ok { val = klass } ->
                match klass.new { args = [] } is
                  Ok { val = list } -> list.method { name = "size", args = [] }
                  Err { error } -> Err { error = error }
              Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        val sizeAsForeign = v.getPayloadFieldByName("val") as MezzeForeign
        val delegate = sizeAsForeign.delegate
        val lib = com.oracle.truffle.api.interop.InteropLibrary.getUncached(delegate)
        assertTrue(lib.isNumber(delegate))
        assertEquals(0L, lib.asLong(delegate))
    }

    @Test
    fun `jvm ArrayList add and size returns 1`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            use std::polyglot::jvm::{ from_int }
            match jvm::class { name = "java.util.ArrayList" } is
              Ok { val = klass } ->
                match klass.new { args = [] } is
                  Ok { val = list } ->
                    match list.method { name = "add", args = [from_int { val = 100 }] } is
                      Ok { val = _ } ->
                        list.method { name = "size", args = [] }
                      Err { error } -> Err { error = error }
                  Err { error } -> Err { error = error }
              Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        val sizeAsForeign = v.getPayloadFieldByName("val") as MezzeForeign
        val lib = com.oracle.truffle.api.interop.InteropLibrary.getUncached(sizeAsForeign.delegate)
        assertEquals(1L, lib.asLong(sizeAsForeign.delegate))
    }

    @Test
    fun `jvm StringBuilder append chain via flat Result chain`() {
        // A flat chain on `Result JvmForeign InteropError` — no `match`
        // between steps. Exercises `impl Result JvmForeign` dispatch: each
        // chained method dispatches on a Result receiver whose type must be
        // INFERRED (the provider has NO return-type annotations). If chain
        // dispatch falls back to the shared MezzeForeign dict, this crashes
        // at runtime (DictFallback). StringBuilder.append is fluent — it
        // returns the builder itself — so the chain is logically valid.
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            use std::polyglot::jvm::{ from_str }
            jvm::class { name = "java.lang.StringBuilder" }
              .new { args = [from_str { val = "a" }] }
              .method { name = "append", args = [from_str { val = "b" }] }
              .method { name = "append", args = [from_str { val = "c" }] }
              .method { name = "toString", args = [] }
              .str {}
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals("abc", v.getPayloadFieldByName("val"))
    }

    @Test
    fun `jvm empty ArrayList size via flat Result chain`() {
        // Simpler flat chain, no fluent-return dependency: new → size → as_int.
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            jvm::class { name = "java.util.ArrayList" }
              .new { args = [] }
              .method { name = "size", args = [] }
              .int {}
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(0L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `jvm ArrayList add then read back element`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            use std::polyglot::jvm::{ from_int }
            match jvm::class { name = "java.util.ArrayList" } is
              Ok { val = klass } ->
                match klass.new { args = [] } is
                  Ok { val = list } ->
                    match list.method { name = "add", args = [from_int { val = 42 }] } is
                      Ok { val = _ } -> list.at { index = 0 }
                      Err { error } -> Err { error = error }
                  Err { error } -> Err { error = error }
              Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        val elt = v.getPayloadFieldByName("val") as MezzeForeign
        val lib = com.oracle.truffle.api.interop.InteropLibrary.getUncached(elt.delegate)
        assertEquals(42L, lib.asLong(elt.delegate))
    }

    @Test
    fun `jvm ArrayList array_size returns Ok 0 for empty list`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            match jvm::class { name = "java.util.ArrayList" } is
              Ok { val = klass } ->
                match klass.new { args = [] } is
                  Ok { val = list } -> list.len{}
                  Err { error } -> Err { error = error }
              Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(0L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `jvm invoke unknown member returns Err`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            match jvm::class { name = "java.util.ArrayList" } is
              Ok { val = klass } ->
                match klass.new { args = [] } is
                  Ok { val = list } -> list.method { name = "no_such_method_xyz", args = [] }
                  Err { error } -> Err { error = error }
              Err { error } -> Err { error = error }
            """.trimIndent()
        )
        assertErr(result)
    }

    @Test
    fun `jvm StringBuilder length via method`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            use std::polyglot::jvm::{ from_str }
            match jvm::class { name = "java.lang.StringBuilder" } is
              Ok { val = klass } ->
                match klass.new { args = [from_str { val = "abc" }] } is
                  Ok { val = sb } -> sb.method { name = "length", args = [] }
                  Err { error } -> Err { error = error }
              Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        val lenForeign = v.getPayloadFieldByName("val") as MezzeForeign
        val lib = com.oracle.truffle.api.interop.InteropLibrary.getUncached(lenForeign.delegate)
        assertEquals(3L, lib.asLong(lenForeign.delegate))
    }

    // ==================== has_member ====================

    @Test
    fun `has_member true for known JVM instance method`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            match jvm::class { name = "java.util.ArrayList" } is
              Ok { val = klass } ->
                match klass.new { args = [] } is
                  Ok { val = list } -> list.has { name = "size" }
                  Err { error = _ } -> False
              Err { error = _ } -> False
            """.trimIndent()
        )
        assertEquals(true, result)
    }

    // ==================== Jvm effect handler ====================

    @Test
    fun `Jvm effect handler routes class lookup`() {
        // The Jvm effect + JvmHost handler routes through the same
        // native the free `jvm::class` fn uses — the handler carries
        // no config, host-access is Context-level.
        val result = eval(
            """
            use std::polyglot::jvm::{ Jvm, JvmHost }
            perform JvmHost in Jvm.class { name = "java.util.ArrayList" }
            """.trimIndent()
        )
        assertOk(result)
    }

    // ==================== type_name ====================

    @Test
    fun `type_name returns a non-empty string`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_int }
            let f = from_int { val = 5 }
            f.type_name{}
            """.trimIndent()
        )
        assertTrue(result is String, "Expected String, got $result")
        assertTrue((result as String).isNotEmpty())
    }

    // ==================== JVM class_of / instanceof ====================

    @Test
    fun `class_of on ArrayList instance returns a Class Foreign`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            match jvm::class { name = "java.util.ArrayList" } is
              Ok { val = klass } ->
                match klass.new { args = [] } is
                  Ok { val = list } -> list.method { name = "getClass", args = [] }
                  Err { error } -> Err { error = error }
              Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        val cls = v.getPayloadFieldByName("val")
        assertTrue(cls is MezzeForeign)
    }

    // NOTE: `.instanceof{}` inherent method is defined but calling it
    // requires a Java `Class` Foreign, not a host-symbol Foreign. The
    // host-symbol returned by `jvm::class` proxies static access to
    // the type; getting the runtime `Class` value requires an extra
    // step (e.g. reading `.class` off a live instance) that the
    // current InteropLibrary surface doesn't expose uniformly across
    // GraalVM versions. Left as a documented affordance for callers
    // that already have a Class Foreign to pass in.

    // ==================== JVM end-to-end: HashMap put/get ====================

    @Test
    fun `jvm HashMap put and get with Mezze string works end-to-end`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            use std::polyglot::jvm::{ from_str, from_int }
            match jvm::class { name = "java.util.HashMap" } is
              Ok { val = klass } ->
                match klass.new { args = [] } is
                  Ok { val = map } ->
                    match map.method {
                      name = "put",
                      args = [from_str { val = "k" }, from_int { val = 99 }]
                    } is
                      Ok { val = _ } ->
                        map.method { name = "get", args = [from_str { val = "k" }] }
                      Err { error } -> Err { error = error }
                  Err { error } -> Err { error = error }
              Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        val got = v.getPayloadFieldByName("val") as MezzeForeign
        val lib = com.oracle.truffle.api.interop.InteropLibrary.getUncached(got.delegate)
        assertEquals(99L, lib.asLong(got.delegate))
    }

    // ==================== Chained let-bound match: static-dispatch bug ====================
    //
    // Regression: same-named inherent methods across the four `<Lang>Foreign`
    // newtypes (they all wrap the same MezzeForeign Java class) crashed at
    // runtime with `DictFallback[invoke_member]: unsupported unary type
    // MezzeForeign` when the solver left an intermediate match result's type
    // as a bare VarType. The receiver dispatch fell through to runtime
    // dict-passing which cannot disambiguate the four candidates on shared
    // Java class alone. Fixed in TypedASTCompiler by structurally recovering
    // the concrete receiver type at compile time — pattern bindings off a
    // concrete scrutinee combined with the polyglot Result-preservation
    // shape (Ok arm threads receiver via `it`, Err arm reconstructs Err) —
    // and routing static dispatch on the recovered type.

    @Test
    fun `chained let-bound match with inherent method dispatches statically`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            use std::polyglot::jvm::{ JvmForeign }
            use std::polyglot::core::{ InteropError }
            let uuid_class = jvm::class { name = "java.util.UUID" }
            let uuid_val = match uuid_class is
              Ok  { val = c } -> c.method { name = "randomUUID", args = [] }
              Err { error }   -> Err { error = error }
            match uuid_val is
              Ok  { val = u } -> u.method { name = "toString", args = [] }
              Err { error }   -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        val got = v.getPayloadFieldByName("val") as MezzeForeign
        val lib = com.oracle.truffle.api.interop.InteropLibrary.getUncached(got.delegate)
        assertTrue(lib.isString(got.delegate), "expected UUID.toString() to yield a String Foreign")
        val s = lib.asString(got.delegate)
        // UUIDs are 36 chars: 8-4-4-4-12
        assertEquals(36, s.length, "UUID string should be 36 chars, got '$s'")
    }

    // ==================== Guest containers (BUG 2) ====================
    //
    // The interop surface could READ a guest array but never BUILD one, so a
    // codec's `encode_list` had no native to call and containers had no
    // `.to_py {}` / `.to_js {}` chain at all. These pin the build side against
    // the read side that already existed.

    @Test
    fun `jvm_list builds a Foreign the array ops can read back`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_int, jvm_list }
            let xs = jvm_list { elems = [from_int { val = 7 }, from_int { val = 9 }] }
            xs.len {}
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(2L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `elements come back in the order they were given`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_int, jvm_list }
            let xs = jvm_list { elems = [from_int { val = 7 }, from_int { val = 9 }] }
            match xs.at { index = 1 } is
              Ok { val = e } -> e.int {}
              Err { error = _ } -> Err { error = UnsupportedMessage }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(9L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `an empty list is a valid guest list, not an error`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ jvm_list }
            let xs = jvm_list { elems = [] }
            xs.len {}
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(0L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `reading past the end is an error, not a crash`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_int, jvm_list }
            let xs = jvm_list { elems = [from_int { val = 7 }] }
            xs.at { index = 5 }
            """.trimIndent()
        )
        assertErr(result)
    }

    @Test
    fun `a built list is accepted as a Java method argument`() {
        // The point of building a guest container is handing it to the guest.
        // `java.util.Arrays.asList` takes a varargs array, so this only works if
        // the value really does answer the interop array messages.
        val result = eval(
            """
            use std::polyglot::jvm::{ JvmHost, Jvm, from_str, jvm_list }
            let main = {} -> perform JvmHost in do
              let xs = jvm_list { elems = [from_str { val = "a" }, from_str { val = "b" }] }
              match Jvm.class { name = "java.util.Arrays" } is
                Ok { val = arrays } -> match arrays.method { name = "asList", args = [xs] } is
                  Ok { val = lst } -> lst.method { name = "size", args = [] }.int {}
                  Err { error = e } -> Err { error = e }
                Err { error = e } -> Err { error = e }
            main {}
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(2L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `jvm_dict builds a Foreign carrying its entries`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_str, from_int, jvm_dict }
            let d = jvm_dict {
              keys = [from_str { val = "a" }],
              vals = [from_int { val = 1 }],
            }
            d.type_name {}
            """.trimIndent()
        )
        assertNotNull(result)
    }

    // ==================== complete-set additions ====================

    @Test
    fun `size unifies array length`() {
        // The unified `size` verb on an array-shaped Foreign (getArraySize branch).
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            use std::polyglot::jvm::{ from_int }
            let list = jvm::class { name = "java.util.ArrayList" }.new { args = [] }
            let _ = list.method { name = "add", args = [from_int { val = 7 }] }
            let _ = list.method { name = "add", args = [from_int { val = 8 }] }
            list.len {}
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(2L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `size unifies hash length`() {
        // The SAME `size` verb on a hash-shaped Foreign (getHashSize branch) — one
        // `len`-like verb over both shapes.
        val result = eval(
            """
            use std::polyglot::jvm::{ from_str, from_int, jvm_dict }
            jvm_dict {
              keys = [from_str { val = "a" }, from_str { val = "b" }, from_str { val = "c" }],
              vals = [from_int { val = 1 }, from_int { val = 2 }, from_int { val = 3 }],
            }.len {}
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(3L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `to_seq drains a non-array iterable through the iterator protocol`() {
        // A HashSet is Iterable but NOT array-shaped, so `to_seq` cannot index it —
        // it must drain the guest iterator. Proves the iterator branch (the array
        // branch is already exercised by the codec's decode_list).
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            use std::polyglot::jvm::{ from_int }
            use std::collections::seq
            let set = jvm::class { name = "java.util.HashSet" }.new { args = [] }
            let _ = set.method { name = "add", args = [from_int { val = 10 }] }
            let _ = set.method { name = "add", args = [from_int { val = 20 }] }
            let _ = set.method { name = "add", args = [from_int { val = 30 }] }
            match set.to_seq {} is
              Ok  { val = xs }  -> seq::size { xs = xs }
              Err { error = _ } -> -1
            """.trimIndent()
        )
        assertEquals(3L, result)
    }

    @Test
    fun `language-effect entry pattern - handler buries Runtime around eval_raw, errors gracefully`() {
        // The EXACT shape every out-of-tree provider uses for its entry: a
        // per-language effect whose handler buries `perform Runtime in
        // Polyglot.eval_raw { lang = "…", source }` with the guest id baked in.
        // The user performs only the language handler; Runtime stays buried. With
        // no such guest language installed on the test classpath, the native
        // surfaces the failure as an Err (GuestError), not a crash — proving the
        // whole entry path (effect -> Runtime -> eval native) is wired and that a
        // missing guest degrades gracefully.
        val result = eval(
            """
            use std::polyglot::core::{ Polyglot, Runtime, InteropError, Foreign }
            newtype XForeign = Foreign
            effect X where { run: { source: Str } -> Result XForeign InteropError <X> }
            pub type XHost = {}
            impl X for XHost where {
              run = { source } ->
                perform Runtime in Polyglot.eval_raw { lang = "nonexistent_lang_xyz", source = source }
            }
            perform XHost in X.run { source = "1 + 1" }
            """.trimIndent()
        )
        assertErr(result)
    }

    @Test
    fun `verbs route through the Polyglot effect with NO enclosing perform`() {
        // The two-layer design's headline property: each JvmForeign verb buries its
        // own `perform Runtime in Polyglot.<op>`, so a whole chain runs with no
        // enclosing perform at all. `Math.PI` read via `.get{}` then coerced via
        // `.float{}` — both verbs delegate to the raw Polyglot effect internally,
        // and the user writes neither `Polyglot.*` nor a `lang` string.
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            jvm::class { name = "java.lang.Math" }
              .get { name = "PI" }
              .float {}
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(Math.PI, v.getPayloadFieldByName("val"))
    }
}
