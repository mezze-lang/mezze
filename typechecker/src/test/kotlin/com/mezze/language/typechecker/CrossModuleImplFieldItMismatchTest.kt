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
}
