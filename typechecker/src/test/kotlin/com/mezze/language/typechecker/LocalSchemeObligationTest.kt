package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * An obligation of a GENERALIZED local `let` stays with that local's scheme; it
 * does not leak onto the enclosing function's signature.
 *
 * `go` used to infer as `{} with Default 'a -> Str`: the helper's `Default 'a`
 * was kept in the enclosing body as well as in the helper's scheme. That is a
 * constraint no caller of `go` can satisfy, and the monomorphizer then
 * specialized `go` at the helper's FIRST use, so `d {}` at `Str` ran `Int`'s
 * default and the program printed `0/0`.
 */
class LocalSchemeObligationTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun typeOf(source: String, name: String): MezzeType? =
        typeChecker.typeCheck(parser.parseParsedModule(source.trimIndent(), "test.mz"), Paths.get("."))
            .getVariableType(name)

    @Test
    fun `a local helper's constraint does not reach the enclosing signature`() {
        val go = typeOf(
            """
            use std::abilities::default::{ Default }
            let go = {} -> do
              let d: {} with Default 'a -> 'a = {} -> Default.default {}
              let n: Int = d {}
              let s: Str = d {}
              "$(n.to_str {})/$(s)"
            """,
            "go",
        ) as MezzeType.FunctionType
        assertEquals(emptyList<MezzeType.FunctionAbilityConstraint>(), go.abilityConstraints)
    }

    /** Control: an obligation on a variable the enclosing function DOES own stays. */
    @Test
    fun `an enclosing function's own constraint is kept`() {
        val go = typeOf(
            """
            use std::abilities::default::{ Default }
            let go: {} with Default 'b -> 'b = {} -> do
              let d: {} with Default 'a -> 'a = {} -> Default.default {}
              d {}
            """,
            "go",
        ) as MezzeType.FunctionType
        assertTrue(go.abilityConstraints.any { it.abilityName == "Default" }, "lost go's own constraint: $go")
    }
}
