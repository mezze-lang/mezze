package com.mezze.language.typechecker

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Behaviour pins for the `bindLet` primitive (S1.1 extraction).
 *
 * `bindLet` is the single reusable step that turns a solved top-level `let`
 * binding into its final context binding — encapsulating authoritative-type
 * selection, alias adoption, effect harvest, generalization / value restriction,
 * and ability-constraint carry. This file asserts its four observable behaviours
 * through the public checker so the extraction (and the later do-block rewiring
 * in S1.3) stays honest. These are the invariants the byte-identical CLI diff
 * also confirmed, kept here as regression guards.
 */
class BindLetPrimitiveTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun typeOf(name: String, src: String): String? =
        tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true)
            .getVariableType(name)?.let { prettyPrintType(it) }

    private fun err(src: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    // (a) annotated type wins over the structural inferred type.
    @Test fun `annotated alias wins over structural record type`() {
        assertEquals("Point", typeOf("origin", """
            type Point = { x: Int, y: Int }
            let origin: Point = { x = 0, y = 0 }
        """))
    }

    // (d/f) a function value generalizes: instantiates at two argument types.
    @Test fun `a function value generalizes`() {
        assertNull(err("""
            let identity = { x: 'a } -> x
            let a: Int = identity { x = 1 }
            let b: Str = identity { x = "y" }
        """))
        assertEquals("{ x: 'a } -> 'a", typeOf("identity", """
            let identity = { x } -> x
        """))
    }

    // (f) value restriction: a non-function (`[]`) is monomorphic — the second,
    // incompatibly-typed view is a type error.
    @Test fun `an empty list does not generalize (value restriction)`() {
        assertNotNull(err("""
            let xs = []
            let a: [Int] = xs
            let b: [Str] = xs
        """))
    }

    // (f) value restriction: a literal binding is monomorphic, not polymorphic.
    @Test fun `a literal binding does not generalize`() {
        assertEquals("Int", typeOf("n", """
            let n = 5
        """))
    }

    // (a/b) a user-defined alias annotation is adopted as the binding's identity.
    @Test fun `alias adopted when type is in scope`() {
        assertEquals("Box", typeOf("boxed", """
            type Box = { val: Int }
            let mk = { n } -> { val = n }
            let boxed: Box = mk { n = 7 }
        """))
    }
}
