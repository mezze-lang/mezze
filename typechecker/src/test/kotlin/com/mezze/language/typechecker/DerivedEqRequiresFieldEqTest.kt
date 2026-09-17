package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue

/**
 * Derivation is CONDITIONAL (Haskell `deriving Eq`, Rust `#[derive(PartialEq)]`):
 * a record / variant / container has `Eq` iff EVERY component has `Eq` — derived
 * or declared. A newtype derives NOTHING, so a newtype with no `impl Eq` poisons
 * every shape that contains it: `==` on that shape must be a TYPE ERROR, never a
 * silent structural compare.
 */
class DerivedEqRequiresFieldEqTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun check(code: String) = typeChecker.typeCheck(parser.parseParsedModule(code, "test.mz"), Paths.get("."))

    private val noEqNewtype = """
        use std::collections::seq
        use std::collections::list
        use std::abilities::compare::{ Eq, Hash }
        newtype N = Int
    """.trimIndent()

    private fun assertRejected(body: String) {
        val e = assertThrows<TypeCheckError> { check("$noEqNewtype\n$body") }
        assertTrue(e.message?.contains("Eq") == true, "expected an Eq error, got: ${e.message}")
    }

    @Test fun `newtype without Eq cannot be compared`() =
        assertRejected("let main = {} -> N.(1) == N.(1)")

    @Test fun `record holding a newtype without Eq cannot be compared`() =
        assertRejected("let main = {} -> { n = N.(1) } == { n = N.(1) }")

    @Test fun `declared record holding a newtype without Eq cannot be compared`() =
        assertRejected("type R = { n: N }\nlet mk: { v: Int } -> R = { v } -> { n = N.(v) }\nlet main = {} -> mk { v = 1 } == mk { v = 1 }")

    @Test fun `seq of a newtype without Eq cannot be compared`() =
        assertRejected("let main = {} -> [ N.(1) ] == [ N.(1) ]")

    @Test fun `list of a newtype without Eq cannot be compared`() =
        assertRejected("let main = {} -> list::from_seq { xs = [ N.(1) ] } == list::from_seq { xs = [ N.(1) ] }")

    @Test fun `option of a newtype without Eq cannot be compared`() =
        assertRejected("let main = {} -> Some { val = N.(1) } == Some { val = N.(1) }")

    @Test fun `variant holding a newtype without Eq cannot be compared`() =
        assertRejected("type W = | Wrap { n: N }, Empty |\nlet main = {} -> Wrap { n = N.(1) } == Wrap { n = N.(1) }")

    @Test fun `seq of a record holding a newtype without Eq cannot be compared`() =
        assertRejected("let main = {} -> [ { n = N.(1) } ] == [ { n = N.(1) } ]")

    @Test fun `once the newtype declares Eq the containing shapes compare`() {
        check(
            """
            $noEqNewtype
            let unn: { n: N } -> Int = { n } -> match n is
              N.(v) -> v
            impl Hash for N where {
              hash = { it } -> __native_structural_hash { it = it }
            }
            impl Eq for N where {
              eq = { it, r } -> unn { n = it } == unn { n = r },
              neq = { it, r } -> if unn { n = it } == unn { n = r } then False else True,
            }
            let a = { n = N.(1) } == { n = N.(1) }
            let b = [ N.(1) ] == [ N.(1) ]
            let c = Some { val = N.(1) } == Some { val = N.(1) }
            let d = list::from_seq { xs = [ N.(1) ] } == list::from_seq { xs = [ N.(1) ] }
            """.trimIndent()
        )
    }
}
