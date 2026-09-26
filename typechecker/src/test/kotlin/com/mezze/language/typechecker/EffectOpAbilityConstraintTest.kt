package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import com.mezze.language.ast.MezzeType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * An effect OPERATION's `with` clause binds exactly as a function's does.
 *
 * It used to parse and vanish. `effectOperationParser` reads the whole signature
 * as a `ParsedFunctionType` — which does carry `abilityConstraints` — and then
 * cherry-picked param / return / effects out of it, so the clause never reached
 * `ParsedEffectOp` at all. `ContextBuilder.addEffectOperations` then built the
 * op's `FunctionType` with no constraints, and since a call site emits
 * `AbilityRequired` from the CALLEE TYPE's constraints, an empty list there means
 * the obligation is silently never checked.
 *
 * Nothing failed, which is what made it invisible: `'a` still instantiated per
 * call site and the effect still propagated — only the obligation was missing.
 * The sibling path (`addAbilityMethods`) had always done this correctly, so the
 * two forms simply disagreed.
 */
class EffectOpAbilityConstraintTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private val effect = """
        use std::abilities::embed::{ Embeddable }
        use std::effects::noop::{ noop }

        effect Ct where {
          ct: { eval: {} -> 'a <*> } with Embeddable 'a -> 'a <Ct>
        }

        impl Ct for CtEval where {
          ct = { eval } -> resume { val = eval {} }
        }
    """.trimIndent()

    private fun err(body: String): String? =
        try {
            typeChecker.typeCheck(
                parser.parseParsedModule("$effect\n$body", "test.mz"),
                Paths.get("."),
                enablePrelude = true,
            )
            null
        } catch (e: TypeCheckError) {
            e.message
        }

    @Test
    fun `a result that does not satisfy the constraint is rejected`() {
        // A function is the canonical non-Embeddable value: `Embeddable` has no
        // impl for one, and cannot be given one.
        val msg = err(
            """
            let bad = {} -> Ct.ct { eval = {} -> do
              noop {}
              { x: Int } -> x + 1
            }
            """.trimIndent()
        )
        assertNotNull(msg, "a function result does not satisfy `with Embeddable 'a` and must be rejected")
        assertTrue(
            msg!!.contains("Embeddable"),
            "the rejection should name the unsatisfied ability; got: $msg",
        )
    }

    /** The over-rejection guard: the constraint must not fire on conforming
     *  results, and `'a` must still instantiate independently per call site. */
    @Test
    fun `conforming results are accepted at independent types`() {
        val result = typeChecker.typeCheck(
            parser.parseParsedModule(
                """
                $effect

                let an_int = {} -> Ct.ct { eval = {} -> do
                  noop {}
                  42
                }

                let a_str = {} -> Ct.ct { eval = {} -> do
                  noop {}
                  "hello"
                }
                """.trimIndent(),
                "test.mz",
            ),
            Paths.get("."),
            enablePrelude = true,
        )

        val intType = result.getVariableType("an_int") as MezzeType.FunctionType
        val strType = result.getVariableType("a_str") as MezzeType.FunctionType
        assertTrue(intType.returnType is MezzeType.IntType, "expected Int, got ${intType.returnType}")
        assertTrue(strType.returnType is MezzeType.StringType, "expected Str, got ${strType.returnType}")

        // ...and the effect still reaches the caller's row.
        assertTrue(
            intType.effects?.effects?.contains("Ct") == true,
            "the op's effect should propagate to the caller; got ${intType.effects}",
        )
    }
}
