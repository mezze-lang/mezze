package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typedast.TypedLetDecl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * An ability constraint must carry exactly as many type arguments as the
 * ability declares.
 *
 * `Priced for 'a` takes one, so a constraint mentioning it renders
 * `with Priced 'a`. It used to render `with Priced 'a 'a` whenever the
 * constraint arrived by instantiating a declared signature: internally the
 * receiver is held apart in `Constraint.AbilityRequired.typeVar`, and putting
 * it back was unconditional even when the source already had it in `typeArgs`.
 */
class AbilityConstraintArityTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun typesOf(src: String): Map<String, MezzeType> {
        val parsed = parser.parseParsedModule(src, "arity.mz")
        val r = typeChecker.checkParsedModule(parsed, Paths.get("."), null, true)
        val success = r as? TypeChecker.TypeCheckResult.Success
            ?: fail("expected the program to typecheck, got: $r")
        return success.typedModule!!.decls
            .filterIsInstance<TypedLetDecl>()
            .associate { it.name to it.scheme.type }
    }

    private val source = """
        ability Priced for 'a where {
          cents: { it: 'a } -> Int
        }

        type Book = { price: Int }

        impl Priced for Book where {
          cents = { it } -> it.price
        }

        let show: { it: 'a } with Priced 'a -> Str = { it } -> it.cents {}.to_str {}

        let viaCall = {} -> show { it = { price = 1 } }
    """.trimIndent()

    @Test
    fun `a one-parameter ability renders one type argument`() {
        val show = typesOf(source)["show"] as MezzeType.FunctionType
        val priced = show.abilityConstraints.single { it.abilityName == "Priced" }
        assertEquals(1, priced.typeArgs.size, "got: ${prettyPrintType(show)}")
    }

    @Test
    fun `instantiating a constrained function does not duplicate the receiver`() {
        // The receiver arrives inside typeArgs here, so putting it back from
        // typeVar would count it twice.
        val viaCall = typesOf(source)["viaCall"] as MezzeType.FunctionType
        for (c in viaCall.abilityConstraints.filter { it.abilityName == "Priced" }) {
            assertEquals(1, c.typeArgs.size,
                "duplicated receiver in: ${prettyPrintType(viaCall)}")
        }
    }
}
