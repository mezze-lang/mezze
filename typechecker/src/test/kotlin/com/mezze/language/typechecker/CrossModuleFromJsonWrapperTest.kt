package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Cross-module `from_json` wrapper TYPECHECK coverage.
 *
 * A wrapper that carries the decode target as a constrained type var
 * (`{ … } with FromJson 'a Str -> Result 'a E`) is DEFINED in one module and
 * CALLED with a concrete target from ANOTHER. The importer imports only the
 * wrapper — NOT `std::codec::json` — so the stdlib codec blanket impls that
 * discharge the wrapper's `FromJson`/`Decode` constraint don't travel with the
 * import, and the ability check used to report:
 *
 *   error[Constraint Solving]: { name: Str }, Str does not implement 'FromJson'.
 *
 * Fixed (2026-09-06) by making the codec blanket impls visible for constraint
 * solving in a user module that doesn't already have codec in scope
 * (`TypeChecker.loadCodecImplContext`, gated by `hasCodecInScope`). The
 * matching runtime (decode-op wiring across the boundary) is exercised by
 * `mez exec` on the app repro; here we lock the typecheck.
 */
class CrossModuleFromJsonWrapperTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearCache()
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearTypeCheckingStack()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun writeModule(name: String, body: String) {
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)
        Files.writeString(srcDir.resolve("$name.mz"), body.trimIndent())
    }

    private fun checkMain(mainSrc: String): Result<TypeCheckResult> {
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)
        Files.writeString(srcDir.resolve("app.mz"), mainSrc.trimIndent())
        return runCatching {
            typeChecker.typeCheck(parser.parseParsedModule(mainSrc.trimIndent(), "app.mz"), tempDir)
        }
    }

    /** The task's minimal repro: a 2-level flat_map/map_err wrapper. */
    @Test
    fun `two-level wrapper, concrete target from importer`() {
        writeModule("dec", """
            use std::codec::json::{ FromJson }
            use std::result::{ flat_map, map_err }
            pub type Err1 = | Boom { m: Str } |
            pub type Resp = { body: Str, url: Str }
            let body_of: { resp: Resp } -> Result Str Str = { resp } -> Ok { val = resp.body }
            pub let decw: { resp: Result Resp Err1 } with FromJson 'a Str -> Result 'a Err1 = { resp } ->
              flat_map {
                result = flat_map { result = resp, f = { val } -> map_err { result = body_of { resp = val }, f = { error } -> Boom { m = error } } },
                f = { val } -> map_err { result = val.from_json {}, f = { error } -> Boom { m = "d" } },
              }
        """)
        val r = checkMain("""
            use dec::{ decw, Resp, Err1 }
            let out: Result { name: Str } Err1 = decw { resp = Ok { val = { body = "{\"name\":\"Ada\"}", url = "u" } } }
        """)
        assertTrue(r.isSuccess) { "should typecheck; got: ${r.exceptionOrNull()?.message}" }
    }

    /** One-combinator wrapper across the boundary, multi-field target. */
    @Test
    fun `one-level wrapper, multi-field target`() {
        writeModule("dec", """
            use std::codec::json::{ FromJson }
            use std::result::{ map_err }
            pub type E2 = | Bad { why: Str } |
            pub let dec2: { s: Str } with FromJson 'a Str -> Result 'a E2 = { s } ->
              map_err { result = s.from_json {}, f = { error } -> Bad { why = "d" } }
        """)
        val r = checkMain("""
            use dec::{ dec2, E2 }
            let out: Result { name: Str, tag: Str } E2 = dec2 { s = "{\"name\":\"Ada\",\"tag\":\"x\"}" }
        """)
        assertTrue(r.isSuccess) { "should typecheck; got: ${r.exceptionOrNull()?.message}" }
    }

    /** Nested imported wrappers: dec3 calls step2, both `with FromJson 'a Str`. */
    @Test
    fun `nested imported wrappers`() {
        writeModule("dec", """
            use std::codec::json::{ FromJson }
            use std::result::{ flat_map, map_err }
            pub type E3 = | Boom { m: Str } |
            pub type Resp = { body: Str, url: Str }
            let body_of: { resp: Resp } -> Result Str Str = { resp } -> Ok { val = resp.body }
            let step2: { s: Str } with FromJson 'a Str -> Result 'a Str = { s } ->
              map_err { result = s.from_json {}, f = { error } -> "e" }
            pub let dec3: { resp: Result Resp E3 } with FromJson 'a Str -> Result 'a E3 = { resp } ->
              flat_map {
                result = flat_map { result = resp, f = { val } -> map_err { result = body_of { resp = val }, f = { error } -> Boom { m = error } } },
                f = { val } -> map_err { result = step2 { s = val }, f = { error } -> Boom { m = "d" } },
              }
        """)
        val r = checkMain("""
            use dec::{ dec3, Resp, E3 }
            let out: Result { name: Str } E3 = dec3 { resp = Ok { val = { body = "{\"name\":\"Ada\"}", url = "u" } } }
        """)
        assertTrue(r.isSuccess) { "should typecheck; got: ${r.exceptionOrNull()?.message}" }
    }
}
