package com.mezze.language.typechecker

import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * An impl operation may only bind parameter names its ability declares.
 *
 * Arguments are records matched by NAME, so a name the ability never declares is
 * never supplied. An un-annotated impl op adopts the DECLARATION's param type
 * (`implOp.paramType ?: expectedParamType` in `AbilityValidator`), so the names
 * it actually binds used to go uncompared.
 *
 * Measured before the check existed: `impl Eq for P where { eq = { it, other }
 * -> … }` against `eq: { it: 'a, r: 'a } -> Bool` passed `mez check`, built, and
 * ran — the op was handed `{it, r}`, the unsatisfied `other` left it a PARTIAL
 * APPLICATION instead of a Bool, and a `set` of two P values that `eq` calls
 * equal reported `size=2`. Silently wrong, and an explicit `Eq.eq { it, other }`
 * call was rejected at the same time, so the impl was held to a signature
 * nothing could call.
 */
class ImplOpParamNamesTest {

    private lateinit var parser: MezzeParser

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
    }

    private fun errorsFrom(src: String): List<TypeCheckError> {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        return try {
            TypeChecker().typeCheck(parser.parseParsedModule(src.trimIndent(), "ops.mz"), Paths.get("."))
            fail("expected the impl to be rejected")
        } catch (e: TypeCheckError) {
            e.all()
        }
    }

    private fun accepts(src: String) {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        TypeChecker().typeCheck(parser.parseParsedModule(src.trimIndent(), "ops.mz"), Paths.get("."))
    }

    @Test
    fun `a parameter the ability does not declare is rejected`() {
        val errors = errorsFrom(
            """
            ability Near for 'a where {
              close: { it: 'a, r: 'a } -> Bool
            }
            type P = { n: Int }
            impl Near for P where {
              close = { it, other } -> it.n == other.n
            }
            let main = {} -> 1
        """
        )
        assertTrue(
            ErrorCode.V0422_IMPL_OP_UNKNOWN_PARAM in errors.map { it.code },
            "binding 'other' where the ability declares 'r' was accepted: ${errors.map { it.message }}"
        )
        val msg = errors.first { it.code == ErrorCode.V0422_IMPL_OP_UNKNOWN_PARAM }.message
        assertTrue("'other'" in msg, "the message must name the offending parameter: $msg")
        assertTrue("r" in msg, "the message must say what the ability declares: $msg")
    }

    /** The error points at the OFFENDING NAME, not at the whole operation —
     *  `nameLocs` carries a position per name. */
    @Test
    fun `the error points at the offending name`() {
        val errors = errorsFrom(
            """
            ability Near for 'a where {
              close: { it: 'a, r: 'a } -> Bool
            }
            type P = { n: Int }
            impl Near for P where {
              close = { it, other } -> it.n == other.n
            }
            let main = {} -> 1
        """
        )
        val loc = errors.first { it.code == ErrorCode.V0422_IMPL_OP_UNKNOWN_PARAM }.loc
        assertEquals(6, loc?.startLine, "expected the operation's own line")
        assertTrue(
            (loc?.startCol ?: 0) > "  close = { it, ".length,
            "expected the caret past `it`, on `other`; got column ${loc?.startCol}"
        )
    }

    @Test
    fun `the declared names are accepted`() = accepts(
        """
        ability Near for 'a where {
          close: { it: 'a, r: 'a } -> Bool
        }
        type P = { n: Int }
        impl Near for P where {
          close = { it, r } -> it.n == r.n
        }
        let main = {} -> 1
    """
    )

    /** Binding FEWER parameters than declared is legitimate — ignoring one is
     *  allowed, and only an UNKNOWN name can never be supplied. */
    @Test
    fun `binding fewer parameters than declared is accepted`() = accepts(
        """
        ability Near for 'a where {
          close: { it: 'a, r: 'a } -> Bool
        }
        type P = { n: Int }
        impl Near for P where {
          close = { it } -> it.n == 0
        }
        let main = {} -> 1
    """
    )

    /** An open pattern keeps its spread; the explicitly named part is still
     *  checked, which is why this is accepted and the next one is not. */
    @Test
    fun `an open pattern over declared names is accepted`() = accepts(
        """
        ability Near for 'a where {
          close: { it: 'a, r: 'a } -> Bool
        }
        type P = { n: Int }
        impl Near for P where {
          close = { it, ... } -> it.n == 0
        }
        let main = {} -> 1
    """
    )

    @Test
    fun `an open pattern does not excuse an unknown name`() {
        val errors = errorsFrom(
            """
            ability Near for 'a where {
              close: { it: 'a, r: 'a } -> Bool
            }
            type P = { n: Int }
            impl Near for P where {
              close = { it, other, ... } -> it.n == other.n
            }
            let main = {} -> 1
        """
        )
        assertTrue(
            ErrorCode.V0422_IMPL_OP_UNKNOWN_PARAM in errors.map { it.code },
            "the spread excused an unknown name: ${errors.map { it.message }}"
        )
    }

    /** An ANNOTATED impl op is checked the same way: the annotation is what the
     *  unification path reads, but the names the body binds are what the caller
     *  must supply. */
    @Test
    fun `a typed parameter list is checked too`() {
        val errors = errorsFrom(
            """
            ability Near for 'a where {
              close: { it: 'a, r: 'a } -> Bool
            }
            type P = { n: Int }
            impl Near for P where {
              close = { it: P, other: P } -> it.n == other.n
            }
            let main = {} -> 1
        """
        )
        assertTrue(
            ErrorCode.V0422_IMPL_OP_UNKNOWN_PARAM in errors.map { it.code },
            "a typed param list hid the unknown name: ${errors.map { it.message }}"
        )
    }

    /** Every parameter wrong, so nothing can be supplied at all — both names are
     *  named, rather than one found and the rest left for the next compile. */
    @Test
    fun `all offending names are listed`() {
        val errors = errorsFrom(
            """
            ability Near for 'a where {
              close: { it: 'a, r: 'a } -> Bool
            }
            type P = { n: Int }
            impl Near for P where {
              close = { lhs, rhs } -> lhs.n == rhs.n
            }
            let main = {} -> 1
        """
        )
        val msg = errors.first { it.code == ErrorCode.V0422_IMPL_OP_UNKNOWN_PARAM }.message
        assertTrue("'lhs'" in msg && "'rhs'" in msg, "both offending names must be named: $msg")
    }
}
