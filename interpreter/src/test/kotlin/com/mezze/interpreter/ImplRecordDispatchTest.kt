package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Probes for impl-method dispatch on plain record types (not newtypes).
 *
 * The question: when there are two distinct nominal record types
 * with overlapping field shapes, can inherent impl-method dispatch
 * pick the right impl by the receiver's *declared* type? This is the
 * scenario that motivated the chainable HTTP API (Request, Response
 * are records and we want methods on each).
 */
class ImplRecordDispatchTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `single record with single impl method`() {
        val result = eval("""
            pub type Foo = { x: Int }
            impl Foo where {
              bump = { it, n: Int } -> { x = it.x + n },
            }
            let f : Foo = { x = 5 }
            (f.bump { n = 3 }).x
        """)
        assertEquals(8L, result)
    }

    @Test
    fun `two records with non-overlapping shapes, same-named method`() {
        // Foo and Bar share no field names. The receiver type at the
        // call site uniquely picks the impl. This should be fine even
        // for the most naive dispatcher.
        val result = eval("""
            pub type Foo = { x: Int }
            pub type Bar = { y: Int }
            impl Foo where { what = { it } -> 1, }
            impl Bar where { what = { it } -> 2, }
            let foo : Foo = { x = 0 }
            let bar : Bar = { y = 0 }
            (foo.what {}) + (bar.what {})
        """)
        // foo.what should be 1, bar.what should be 2, sum = 3.
        assertEquals(3L, result)
    }

    @Test
    fun `two records with partially overlapping shapes, distinct method names`() {
        // Different methods on each — no name collision, but field
        // overlap (both have `headers`). The receiver type at the
        // call site is annotated; the dispatcher should pick the impl
        // matching the declared type.
        val result = eval("""
            pub type Req = { method: Str, url: Str, headers: Int }
            pub type Resp = { status: Int, headers: Int }
            impl Req where {
              method_name = { it } -> it.method,
            }
            impl Resp where {
              ok = { it } -> it.status >= 200,
            }
            let r : Resp = { status = 204, headers = 0 }
            if r.ok {} then 1 else 0
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `two records with partially overlapping shapes, same-named methods on each`() {
        // The case that bit us in HTTP: both impls expose `is_ok`.
        // The receiver type is annotated, so dispatch by declared type
        // should work.
        val result = eval("""
            pub type Req = { method: Str, url: Str, headers: Int }
            pub type Resp = { status: Int, headers: Int }
            impl Req where {
              is_ok = { it } -> True,
            }
            impl Resp where {
              is_ok = { it } -> it.status >= 200,
            }
            let r : Resp = { status = 204, headers = 0 }
            if r.is_ok {} then 1 else 0
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `inferred record type with two impls — picks correct method by declared annotation`() {
        // Variant: caller has no annotation, the record literal infers.
        // Each impl has a unique method name. The dispatcher must be
        // able to pick by the inferred shape — `Resp` has `status`,
        // `Req` does not.
        val result = eval("""
            pub type Req = { method: Str, url: Str }
            pub type Resp = { status: Int }
            impl Req where {
              method_name = { it } -> it.method,
            }
            impl Resp where {
              ok = { it } -> it.status >= 200,
            }
            # No annotation on r; inferred from literal.
            let r = { status = 204 }
            if r.ok {} then 1 else 0
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `Bug repro - third impl with method that builds a literal of another impl record type`() {
        // This mirrors what happens in http.mz: there's a Client impl
        // whose `get` method returns a Request literal. Three impls
        // coexist in one module — does that confuse dispatch?
        val result = eval("""
            pub type Req = { method: Str, url: Str }
            pub type Resp = { status: Int }
            pub type Client = { base: Str }

            impl Req where {
              send = { it } -> it.url,
            }
            impl Resp where {
              ok = { it } -> it.status >= 200,
            }
            impl Client where {
              # The `get` method builds a Req literal — does the
              # typechecker correctly type `it` as Client here?
              get = { it, path: Str } -> { method = "GET", url = path },
            }

            let c : Client = { base = "" }
            let r = c.get { path = "/x" }
            r.send {}
        """)
        assertEquals("/x", result)
    }

    @Test
    fun `Bug repro - two impls, methods delegate to free fns whose param matches receiver type`() {
        // This is closer to http.mz: each impl method is a thin
        // delegator to a free function. The free function's parameter
        // shares its NAME with no receiver, but its TYPE is the
        // receiver type. Does the harvest of inherent-impl-op-return-
        // types get confused?
        val result = eval("""
            pub type Req = { method: Str, url: Str }
            pub type Resp = { status: Int }

            pub let req_send : { r: Req } -> Str = { r } -> r.url
            pub let resp_ok : { r: Resp } -> Bool = { r } -> r.status >= 200

            impl Req where {
              send = { it } -> req_send { r = it },
            }
            impl Resp where {
              ok = { it } -> resp_ok { r = it },
            }

            let resp : Resp = { status = 204 }
            if resp.ok {} then 1 else 0
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `Regression #180 - impl method body's effects propagate to call site`() {
        // The impl method body performs <Foo>. Before #180 the
        // ConstraintGenerator built an effectless FunctionType for
        // inherent impl ops (only paramType + returnType), so the
        // call site `r.go {}` was reported as pure and the
        // surrounding `perform FooImpl` failed with "Expression is
        // pure (has no effects), but handlers were provided".
        // Fix: include bodyResult.effects in the impl method's
        // FunctionType when stashing it for TypedAstBuilder.
        val result = eval("""
            effect Foo where {
              do_it: { x: Str } -> Str <Foo>,
            }

            pub type R = { url: Str }

            impl R where {
              go = { it } -> Foo.do_it { x = it.url },
            }

            impl Foo for FooImpl where {
              do_it = { x } -> x,
            }

            perform FooImpl in do
              let r : R = { url = "hello" }
              r.go {}
        """)
        assertEquals("hello", result)
    }
}
