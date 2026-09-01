package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Probe: what does newtype actually do today?
 *
 * These tests exist to observe behavior, not pin it. Every test prints
 * PASS/REJECT to stdout so we can see the actual state without inference.
 */
class NewtypeProbeTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun run(label: String, src: String): String? {
        return try {
            tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "test.mz"), Paths.get("."), enablePrelude = true)
            println("[$label] PASSES")
            null
        } catch (e: Exception) {
            val msg = e.message?.take(300) ?: e.javaClass.simpleName
            println("[$label] REJECTS: $msg")
            msg
        }
    }

    @Test
    fun `Q1_newtype_construction_and_use`() {
        // Basic: define newtype, construct, use in function signature.
        run("Q1a. basic construct + use (positional)",
            """
            pub newtype UserId = Str
            pub let take = { u: UserId } -> 42
            pub let f = {} -> take { u = UserId.("abc") }
            """)
        run("Q1b. newtype-over-record construct",
            """
            pub newtype Point = { x: Int, y: Int }
            pub let make = {} -> Point { x = 1, y = 2 }
            """)
    }

    @Test
    fun `Q2_impl_Eq_for_newtype`() {
        run("Q2. impl Eq for UserId",
            """
            pub newtype UserId = Str
            impl Eq for UserId where {
              eq  = { it, r } -> __native_str_eq { l = it, r = r },
              neq = { it, r } -> if __native_str_eq { l = it, r = r } then False else True,
            }
            pub let a : UserId = UserId.("u1")
            pub let b : UserId = UserId.("u2")
            pub let f = {} -> a == b
            """)
    }

    @Test
    fun `Q3_bare_str_rejected_as_UserId`() {
        run("Q3. bare Str where UserId expected — should REJECT",
            """
            pub newtype UserId = Str
            pub let take = { u: UserId } -> 42
            pub let f = {} -> take { u = "just a string" }
            """)
    }

    @Test
    fun `Q4_native_str_op_inside_newtype_impl`() {
        // Post-#109: __native_* is stdlib-only. User calls to natives REJECT
        // as undefined variables, which incidentally closes the opacity leak
        // in Q4a/Q4f. Q4d still requires stdlib to call natives on wrapped
        // values inside impl bodies — but user code just can't reach natives.

        // Q4a: __native_str_eq with UserId directly — user code REJECTS (native access)
        run("Q4a. user code, native str op on UserId — REJECTS (native inaccessible)",
            """
            pub newtype UserId = Str
            pub let equal = { a: UserId, b: UserId } -> __native_str_eq { l = a, r = b }
            """)

        // Q4b: user-level str::size on UserId — should reject.
        run("Q4b. str::size on UserId — should REJECT",
            """
            use std::str
            pub newtype UserId = Str
            pub let f = { u: UserId } -> str::size { s = u }
            """)

        // Q4c: PASSING UserId where Str expected in a plain fn — should reject.
        run("Q4c. plain fn taking Str, given UserId — should REJECT",
            """
            pub newtype UserId = Str
            pub let take_str = { s: Str } -> 1
            pub let f = { u: UserId } -> take_str { s = u }
            """)

        // Q4d: user code with pattern-match then native — REJECTS on native access.
        run("Q4d. user code, pattern-match unwrap then native — REJECTS (native inaccessible)",
            """
            pub newtype UserId = Str
            pub let equal = { a: UserId, b: UserId } ->
              match a is UserId.(x) ->
                match b is UserId.(y) ->
                  __native_str_eq { l = x, r = y }
            """)

        // Q4e: user calling __native_str_eq on plain Str — still REJECTS.
        run("Q4e. user code calling __native_str_eq on Str — REJECTS (native inaccessible)",
            """
            pub let f = { a: Str, b: Str } -> __native_str_eq { l = a, r = b }
            """)

        // Q4f: user code, __native_str_eq with UserIds — REJECTS on native access
        //      (also would have leaked opacity but that's moot now).
        run("Q4f. user code, __native_str_eq with UserIds — REJECTS (native inaccessible)",
            """
            pub newtype UserId = Str
            pub let f = { a: UserId, b: UserId } -> __native_str_eq { l = a, r = b }
            """)

        // Q4g: user declaring __native_foo — REJECTS at parse (existing check).
        run("Q4g. user pub let __native_foo — REJECTS (reserved prefix)",
            """
            pub let __native_foo = { x: Int } -> x
            """)
    }

    @Test
    fun `Q5_two_module_same_named_newtypes`() {
        // Actually two decls in ONE module — but same name should collide anyway.
        run("Q5. duplicate newtype names — same shape",
            """
            pub newtype Id = Str
            pub newtype Id = Str
            """)
    }

    @Test
    fun `Q6_type_alias_accepts_bare_type`() {
        // Confirm type alias vs newtype distinction: `type` accepts underlying.
        run("Q6. type alias accepts bare Str",
            """
            pub type Email = Str
            pub let take = { e: Email } -> 42
            pub let f = {} -> take { e = "just a string" }
            """)
    }

    @Test
    fun `Q7_generic_impl_on_newtype`() {
        // Does an ability method on a newtype receiver resolve?
        run("Q7. .to_dbg on newtype (auto-derived Inspect)",
            """
            pub newtype UserId = Str
            pub let a : UserId = UserId.("u1")
            pub let s = a.to_dbg {}
            """)
    }
}
