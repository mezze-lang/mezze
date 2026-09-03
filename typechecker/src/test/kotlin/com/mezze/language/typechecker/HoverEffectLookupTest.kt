package com.mezze.language.typechecker

import com.mezze.language.parsedast.ParsedEffectDecl
import com.mezze.language.parser.MezzeParser
import com.mezze.language.langservice.hover.HoverInfo
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Hover has to find an effect declaration that is not in the file being
 * hovered.
 *
 * `impl Log for Console` renders each operation as `name: Param -> Ret`, and
 * for an imported impl the operation types are null — the signature comes from
 * the effect declaration instead. effectDeclByName therefore searches local
 * decls, then the imported effects on the check result, then every loaded
 * module.
 *
 * Those last two fallbacks were lost when hover moved out of the LSP, and the
 * failure was quiet: the ops degraded to bare names (`{ log }` rather than
 * `{ log: { msg: Str } -> {} }`) with no error anywhere.
 */
class HoverEffectLookupTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun `an effect declared outside the file is still found`() {
        // Nothing in this source declares Log; it comes from the stdlib, so
        // the local lookup misses and a fallback has to answer.
        val src = """
            use std::effects::log::{ Console, println }

            let main = {} -> perform Console in println { msg = "hi" }
        """.trimIndent()

        val parsed = parser.parseParsedModule(src, "hover.mz")
        val r = typeChecker.checkParsedModule(parsed, Paths.get("."), null, true)
        val success = r as? TypeChecker.TypeCheckResult.Success
            ?: fail("expected it to typecheck, got: $r")

        val ctx = HoverInfo.Context(
            content = src,
            typeCheckResult = success.toHoverResult(),
            typeAliases = typeChecker.getTypeAliases(),
            parsedModule = parsed,
        )

        val decl: ParsedEffectDecl? = HoverInfo.effectDeclByName(ctx, ctx.typeCheckResult, "Log")
        assertNotNull(decl, "Log is imported, not local — a fallback must find it")
        assertTrue(
            decl!!.operations.any { it.name == "log" && it.paramType != null },
            "the decl must carry op types, or the impl hover shows bare names",
        )
    }
}
