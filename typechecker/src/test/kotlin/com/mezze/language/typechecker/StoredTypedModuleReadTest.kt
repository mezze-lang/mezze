package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.store.StoreSerialization
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * The stdlib loaders read a module the way a store writes it.
 *
 * A store writes a checked module with its types shared; the loaders decoded it
 * as plain CBOR, which fails on the first shared type. The failure was counted
 * and logged only under debug, so a stdlib loaded from a store had no typed
 * modules — and nothing it lowered through that path at all.
 */
class StoredTypedModuleReadTest {

    @Test
    fun `a module read from a store is the module that was written`() {
        val source = """
            type Point = { x: Int, y: Int }
            let origin = {} -> { x = 0, y = 0 }
            let shift = { p: Point, by: Int } -> { x = p.x + by, y = p.y + by }
            let twice = { p: Point } -> shift { p = shift { p = p, by = 1 }, by = 1 }
        """.trimIndent()
        val parsed = MezzeParser().parseParsedModule(source, "app.mz")
        val checked = TypeChecker().checkParsedModule(parsed, Paths.get("."), enablePrelude = true)
            as TypeChecker.TypeCheckResult.Success
        val written = checked.typedModule!!
        assertTrue(written.decls.size >= 3, "nothing to compare: ${written.decls.size} declarations")

        val read = readStoredTypedModule(StoreSerialization.serializeTypedModule(written))

        assertEquals(written.hash, read.hash)
        assertEquals(written.decls, read.decls, "the module's declarations came back different")
    }
}
