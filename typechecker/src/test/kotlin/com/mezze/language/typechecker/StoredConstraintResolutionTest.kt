package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A stored ability constraint must say what solving concluded, not what it
 * started from.
 *
 * `addAbilityConstraintsToFunctionType` carried a constraint over from the
 * incoming type with its ORIGINAL type variables. Solving had usually bound
 * them, but that answer lived only in the substitution — applied when printing
 * a signature and nowhere else. So a binding whose constraint WAS resolved
 * still stored bare variables, and later passes re-derived a weaker answer:
 *
 *     describe : { body: Str } with FromJson 'a 'b -> Str
 *
 * That signature is a promise no caller can keep, because `'a` and `'b` appear
 * in neither the parameters nor the result. The fix substitutes the arguments
 * where the constraint is stored.
 */
class StoredConstraintResolutionTest {

    private fun signatureOf(src: String, binding: String): String {
        TypeChecker.clearCache()
        val parsed = MezzeParser().parseParsedModule(src, "t.mz")
        val r = TypeChecker().checkParsedModule(parsed, Paths.get("."), null, true)
        val success = r as? TypeChecker.TypeCheckResult.Success
            ?: error("expected the program to typecheck, got: $r")
        val decl = success.typedModule!!.decls
            .filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
            .find { it.name == binding }
            ?: error("no binding named $binding")
        return com.mezze.language.ast.prettyPrintType(decl.scheme.type)
    }

    @Test
    fun `an inferred binding does not carry variables absent from its signature`() {
        val sig = signatureOf(
            """
            use std::codec::json::{ parse_str, FromJson }

            let describe = { body } -> parse_str { s = body }
              .to_option {}
              .flat_map { f = { val } -> val.from_json {}.to_option {} }
              .map { f = { val } -> val.code }
              .get_or { def = "?" }
            """.trimIndent(),
            "describe",
        )
        // Whatever else it says, it must not promise a constraint over type
        // variables the caller cannot see and therefore cannot pin.
        assertFalse(
            sig.contains("FromJson 'a") || sig.contains("FromJson 'b"),
            "signature keeps unresolvable constraint variables: $sig",
        )
    }

    @Test
    fun `genuine polymorphism keeps its constraint`() {
        // The guard above must not throw away a real constraint: here `'a` IS
        // visible in the parameter, so a caller pins it and the impl resolves.
        val sig = signatureOf(
            """
            ability Priced for 'a where { cents: { it: 'a } -> Int }
            let show = { it } -> it.cents {}
            """.trimIndent(),
            "show",
        )
        assertTrue(sig.contains("Priced"), "a satisfiable constraint must be kept: $sig")
    }
}
