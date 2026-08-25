package com.mezze.language.typechecker

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.ast.prettyPrintTypeWithAliases
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Variant VALUES adopt their matching alias name at construction — the mirror
 * of the record adoption ([RecordAliasAdoptionTest]). Variants already carried
 * `typeName` through construction (records did not, hence B); this pins it.
 *
 * Ambiguity is impossible for variants: the duplicate-constructor rule forbids
 * two aliases from sharing an arm name, so any constructor maps to exactly one
 * alias. Cross-module still requires `pub` + import for the alias to be in scope.
 */
class VariantAliasAdoptionTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun typeOf(name: String, b: String): String? {
        val r = tc.typeCheck(parser.parseParsedModule(b.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true)
        return r.getVariableType(name)?.let { prettyPrintTypeWithAliases(it, tc.getTypeAliases()) }
    }

    @Test
    fun `a bare no-payload constructor adopts its variant alias`() {
        assertEquals("Color", typeOf("x", "type Color = | Red, Green, Blue |\nlet x = Red"))
    }

    @Test
    fun `a single-arm value adopts`() {
        assertEquals("Only", typeOf("x", "type Only = | Solo |\nlet x = Solo"))
    }

    @Test
    fun `a payload constructor adopts`() {
        assertEquals("Box", typeOf("x", "type Box = | Boxed { val: Int } |\nlet x = Boxed { val = 5 }"))
    }

    @Test
    fun `an explicit annotation still names it`() {
        assertEquals("Color", typeOf("x", "type Color = | Red, Green, Blue |\nlet x: Color = Red"))
    }

    @Test
    fun `a constructor maps to exactly one alias (disjoint aliases in scope)`() {
        assertEquals("Color", typeOf("x", "type Color = | Red, Green |\ntype Size = | Small, Big |\nlet x = Red"))
    }

    @Test
    fun `an imported variant alias is adopted`() {
        val dir = Files.createTempDirectory("vadopt_xmod")
        fun mod(n: String, s: String) = Files.writeString(dir.resolve("$n.mz"), s.trimIndent())
        mod("colors", "pub type Color = | Red, Green, Blue |")
        mod("user", """
            use colors::{ Color, Red }
            pub let x = Red
        """)
        val r = tc.typeCheck(parser.parseParsedModule(Files.readString(dir.resolve("user.mz")), "user.mz"), dir)
        assertEquals("Color", r.getVariableType("x")?.let { prettyPrintTypeWithAliases(it, tc.getTypeAliases()) })
    }
}
