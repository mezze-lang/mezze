package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A generic method call on a multi-param ability must carry the ability
 * requirement EXACTLY ONCE on the inferred function type. Previously two
 * FunctionAbilityConstraints appeared on `{x,y} -> x.new_add {r=y}`:
 *   - the correct multi-arg one (receiver + arg) from the call-site path, and
 *   - a malformed 1-arg one from ConstraintGenerator.addAbilityConstraintsTo
 *     FunctionType, which hardcoded `typeArgs = listOf(matchingTypeVar)` and
 *     dropped the extra ability type args. Because the two had different arg
 *     lists, the downstream distinctBy couldn't collapse them.
 * Both are satisfiable so nothing broke, but the duplicate would double up in
 * hover/signatures and could double an error message. The fix carries the full
 * arg tuple so the constraint is well-formed and dedups naturally.
 */
class MultiParamConstraintDedupTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private val prelude = """
        ability NewAdd 'r for 'l having 'out where {
          new_add: { it: 'l, r: 'r } -> 'out
        }
        impl NewAdd Int for Int having 'out = Int where {
          new_add = { it, r } -> it + r
        }
    """.trimIndent()

    @Test
    fun `generic multi-param method call emits the ability constraint once`() {
        val parsed = parser.parseParsedModule(
            prelude + "\n\npub let my_func = { x, y } -> x.new_add { r = y }", "test.mz"
        )
        val r = typeChecker.typeCheck(parsed, Paths.get("."), enablePrelude = true)
        val fnType = r.getVariableType("my_func") as? MezzeType.FunctionType
        assertNotNull(fnType, "my_func should typecheck")

        val newAddConstraints = fnType!!.abilityConstraints.filter { it.abilityName == "NewAdd" }
        assertEquals(
            1, newAddConstraints.size,
            "expected exactly one NewAdd constraint, got: $newAddConstraints"
        )
        // And the surviving one must be the full 2-arg form (receiver + arg),
        // not the malformed single-arg version.
        assertEquals(
            2, newAddConstraints.first().typeArgs.size,
            "the NewAdd constraint should carry both type args (receiver + arg), got: ${newAddConstraints.first().typeArgs}"
        )
    }
}
