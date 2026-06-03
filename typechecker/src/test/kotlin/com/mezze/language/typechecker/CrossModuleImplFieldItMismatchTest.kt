package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Pinning #177 — the typechecker fails on import-time re-typecheck of a
 * module that declares two impls on different nominal record types,
 * where one impl method shares both:
 *   (a) the same method name pattern that exists on another impl, AND
 *   (b) a second-parameter name (e.g. `{it, name}`) that creates the
 *       observable error "Field 'it' type mismatch: expected X, got Y".
 *
 * Stage B's http.mz triggers this with Request/Response/Client impls.
 * Goal: shrink to the smallest reproducer that fails the same way, then
 * use printlns to find which expression in the module emits the bad
 * constraint.
 */
class CrossModuleImplFieldItMismatchTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    /**
     * Smallest reproducer aimed at #177.
     *
     * The "library" module declares:
     *   - two distinct record types `Req` and `Resp`
     *   - `impl Req` with a method `with_extra { it, name: Str } -> Req`
     *     whose body delegates to a same-named free fn `with_extra`
     *   - `impl Resp` with a method `with_extra { it, name: Str } -> Resp`
     *     whose body delegates to a different same-named free fn
     *
     * The "user" module imports both types and exercises the methods.
     * Expected: clean typecheck. Actual: import-time global solve fails
     * with "Field 'it' type mismatch: expected Resp, got Req" (or
     * vice versa).
     */
    @Test
    fun `cross-module impl with overlapping method name plus param shape`(@TempDir tempDir: Path) {
        val libModule = tempDir.resolve("libmod.mz")
        libModule.writeText("""
            pub type Req = { url: Str }
            pub type Resp = { status: Int }

            pub let with_extra_req : { r: Req, name: Str } -> Req = { r, name } ->
              { url = name }
            pub let with_extra_resp : { r: Resp, name: Str } -> Resp = { r, name } ->
              { status = 1 }

            impl Req where {
              with_extra = { it, name: Str } -> with_extra_req { r = it, name = name },
            }
            impl Resp where {
              with_extra = { it, name: Str } -> with_extra_resp { r = it, name = name },
            }
        """.trimIndent())

        val userSource = """
            use libmod::{Req, Resp}

            let r : Resp = { status = 0 }
            let r2 = r.with_extra { name = "x" }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(userSource, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, tempDir)

        val r2Type = typedProgram.getVariableType("r2")
        println("[#177] r2 type: ${r2Type?.let { prettyPrintType(it) } ?: "null"}")

        // Expected behaviour: r2 should be Resp.
        // Current behaviour: this either fails to typecheck OR returns
        // an unresolved type variable. Either way we want to surface it.
        assertNotNull(r2Type, "r2 should be type-checked")
    }

    /**
     * Pinning #182 — chainable HTTP `api.get { path }.send {}` should
     * return `Result Resp Err`, but the typechecker types it as `Req`
     * (the receiver of `.send`). Sub-pattern: imported impl method's
     * return type binds to the receiver rather than the method's own
     * declared/inferred return.
     *
     * Minimal shape mimicking Stage B chainable HTTP:
     *   Client.get { path }       -> Req
     *   Req.send {}               -> Result Resp Err
     *
     * Then `c.get { path }.send {}` should type as `Result Resp Err`,
     * NOT `Req`.
     */
    @Test
    fun `cross-module impl method chain - return type from method not receiver`(@TempDir tempDir: Path) {
        // Closer mimicry of Stage B http.mz Client/Request/Response: an
        // Http effect with a request op, MockHttpClient impl, plus
        // impl Client where { get } and impl Request where { send }.
        // The test imports the lib and chains `c.get { path }.send {}`
        // inside a perform block then matches on the Result.
        // Cross-module: lib defines impls, test imports.
        //
        // Critical detail: `pub let get` is ALSO exported as a free
        // function with a different return type. Previously the harvest
        // path preferred the export's return type for the impl op
        // (silent conflation), corrupting the chain.
        // Closer to http.mz: fields use TypeApplication types
        // (Option, Map) which expand differently from plain records.
        val libModule = tempDir.resolve("libmod2.mz")
        libModule.writeText("""
            use std::collections::map

            pub type Method = | Get, Post |
            pub type Req = {
              method: Method,
              url: Str,
              headers: Map Str Str,
              body: Option Bytes,
              timeout_ms: Option Int,
            }
            pub type Resp = { status: Int }
            pub type Err = | Failed { msg: Str } |
            pub type Client = { base: Str, default_timeout: Int, default_headers: Map Str Str }

            ## MyHttp effect with a `request` op that consumes Req and
            ## returns Result Resp Err — mirrors http.mz's Http effect.
            effect MyHttp where {
              request: { req: Req } -> Result Resp Err <MyHttp>
            }

            impl MyHttp for MockMyHttp { canned: Resp } where {
              request = { req } -> Ok { val = canned },
            }

            ## Same-named free function — different return type. The
            ## harvest path used to prefer THIS as the source of truth,
            ## which corrupted Client.get's chain.
            pub let get : { url: Str } -> Result Resp Err <MyHttp> = { url } ->
              MyHttp.request { req = {
                method = Get, url = url, headers = map::empty {},
                body = None, timeout_ms = None,
              } }

            ## Factory function — mirrors http.mz's `client` constructor.
            pub let client : { base_url: Str } -> Client = { base_url } ->
              { base = base_url, default_timeout = 30000, default_headers = map::empty {} }

            impl Client where {
              get = { it, path: Str } -> {
                method = Get,
                url = path,
                headers = it.default_headers,
                body = None,
                timeout_ms = Some { val = it.default_timeout },
              },
            }
            impl Req where {
              send = { it } -> MyHttp.request { req = it },
            }
        """.trimIndent())

        val userSource = """
            use libmod2::{Client, Req, Resp, Err, Method, client, MyHttp, MockMyHttp}

            let run : {} -> Int = {} ->
              perform MockMyHttp { canned = { status = 200 } } in do
                let c = client { base_url = "https://api.acme.com" }
                let resp = c.get { path = "/x" }.send {}
                match resp is
                  Ok { val } -> val.status
                  Err { error = _ } -> -1
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(userSource, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, tempDir)

        val runType = typedProgram.getVariableType("run")
        println("[#182] run type: ${runType?.let { prettyPrintType(it) } ?: "null"}")
        assertNotNull(runType)
        // run should be {} -> Int — confirms the chain typed successfully.
        val rs = runType?.let { prettyPrintType(it) } ?: ""
        assertTrue(rs.contains("Int"), "run should produce Int, got $rs")
    }

    /**
     * Pinning #183 — 3-link chain `c.get { path }.with_json { val }.send {}`
     * fails with "Type constructor mismatch: Request vs Client". The
     * receiver of `.with_json` is the result of `c.get { path }` (a
     * Request, by the impl's return annotation), but the typechecker
     * is unifying it with Client (the receiver of `.get`). Likely an
     * issue with how `inherentMethods.size > 1` dispatch picks the
     * wrong impl when method names overlap across types.
     */
    @Test
    fun `3-link chain - second method dispatches on prev method's return`(@TempDir tempDir: Path) {
        val libModule = tempDir.resolve("libmod3.mz")
        libModule.writeText("""
            pub type Req = { method: Str, body: Option Str }
            pub type Resp = { status: Int }
            pub type Err = | Failed { msg: Str } |
            pub type Client = { base: Str }

            ## Helper to pin the return type to Req in Client.get's body.
            pub let make_req : { method: Str, body: Option Str } -> Req = { method, body } ->
              { method = method, body = body }

            impl Client where {
              get = { it, path: Str } -> make_req { method = "GET", body = None },
              ## Overlap: Client also has `with_json` to test dispatch
              ## disambiguation. Should NOT be picked when receiver is Req.
              with_json = { it, val: Str } -> { base = val },
            }
            impl Req where {
              with_json = { it, val: Str } -> make_req { method = it.method, body = Some { val = val } },
              send = { it } -> do
                let resp : Resp = { status = 200 }
                let r : Result Resp Err = Ok { val = resp }
                r,
            }
        """.trimIndent())

        val userSource = """
            use libmod3::{Client, Req, Resp, Err}

            let c : Client = { base = "" }
            let req = c.get { path = "/x" }.with_json { val = "hello" }
            let resp = req.send {}
            let status = match resp is
              Ok { val } -> val.status
              Err { error = _ } -> -1
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(userSource, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, tempDir)
        val reqType = typedProgram.getVariableType("req")
        val respType = typedProgram.getVariableType("resp")
        val statusType = typedProgram.getVariableType("status")
        println("[#183] req type: ${reqType?.let { prettyPrintType(it) } ?: "null"}")
        println("[#183] resp type: ${respType?.let { prettyPrintType(it) } ?: "null"}")
        println("[#183] status type: ${statusType?.let { prettyPrintType(it) } ?: "null"}")
        assertNotNull(reqType)
        assertNotNull(respType)
        assertNotNull(statusType)
        val respStr = respType?.let { prettyPrintType(it) } ?: ""
        assertTrue(respStr.contains("Resp") && respStr.contains("Err"),
            "resp should be Result Resp Err, got $respStr")
    }

    /**
     * Pinning #182 against real stdlib's std::effects::http — the actual
     * failing surface from HttpChainTest. The synthetic test above
     * passes after the four-part fix; this one currently fails because
     * stdlib http.mz's `impl Client where { get }` body relies on
     * `it.<field>` accesses to construct a Request record. The home-
     * module solver pins `it`'s shape via HasField constraints, but
     * the lambda's stored type loses the link from each field's
     * TypeVar to the actual nominal types (Map Str Str, Option Bytes,
     * Option Int, Method). On import, the lambda becomes
     *   { it: 'a, path: Str } -> { method: 'd, url: Str, headers: 'c,
     *                              body: 'b, timeout_ms: 'e }
     * and the call site can't recover `it: Client` ⇒ the structural
     * record can't be unified back to `Request` either. This is a
     * deeper polymorphic-impl serialization issue — left here as a
     * pinning test to drive the next fix.
     */
    @Test
    fun `stdlib http chain - api dot get dot send`(@TempDir tempDir: Path) {
        val userSource = """
            use std::effects::http::{Http, MockHttpClient, MockRule, UrlPattern, Response, Method, client}
            use std::collections::map
            use std::collections::bytes

            let run : {} -> Int = {} ->
              perform MockHttpClient { rules = [
                { methods = [Get], url = Glob { pattern = "**" },
                  response = Ok { val = {
                    status = 200, headers = map::empty {}, body = bytes::from_string { s = "ok" }
                  } } }
              ] } in do
                let api = client { base_url = "https://api.acme.com" }
                let resp = api.get { path = "/users/42" }.send {}
                match resp is
                  Ok { val } -> val.status
                  Err { error = _ } -> -1
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(userSource, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, tempDir)
        val runType = typedProgram.getVariableType("run")
        println("[#182-real] run type: ${runType?.let { prettyPrintType(it) } ?: "null"}")
        assertNotNull(runType)
    }
}
