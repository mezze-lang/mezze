package com.mezze.language.typechecker

import com.mezze.language.ast.TypeVar
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.fail

/**
 * "The compiler did not determine a type here" is a bounded, named set of places.
 *
 * It used to be six inline `?: VarType(freshWithName("unknown"))` expressions, and
 * the value they produced was indistinguishable from an ordinary type variable the
 * moment it left the function. Downstream passes read it as polymorphism — which
 * is what a type variable means — and guessed from it. That is how a VARIANT
 * comparison came to be answered by `Eq Int`'s operation: mono treats a variable
 * receiver as "the specialization's evidence decides", and the specialization
 * happened to carry `Eq Int`.
 *
 * There is one way to mint one now ([TypeVar.unknown]), it carries a REASON, and
 * [TypeVar.isPlaceholder] is how a consumer that would otherwise guess can tell it
 * from a real variable. This gate holds the set of reasons: a new one means a new
 * place where the compiler gave up on a type, and that should be a deliberate act
 * with a line in this list, not a quiet `?:`.
 *
 * Shrinking it is the goal — each entry is a place A.2 has to reach.
 */
class UnknownPlaceholderGateTest {

    /**
     * Every reason the compiler can mint an unknown placeholder for.
     *
     * The list used to be six BLANKET reasons, and the largest of them — "no
     * context type at buildExpr" — was a DEFAULT PARAMETER, so it fired on every
     * context-free build in the compiler and one entry covered every position
     * that gave up. Absence of a context is now `null`, which is what it is, and
     * each genuine last resort says which position it is.
     *
     * Three of the old six are gone entirely, replaced by a loud failure rather
     * than a placeholder, because reaching them means emit ran on something that
     * should not have type-checked: a `let` or destructuring `let` with no solved
     * type, and a qualified name in no import table. A fourth — the module's
     * trailing expression — reads the type the generator already recorded under
     * `_toplevel` instead of inventing one.
     *
     * Measured over the stdlib plus five programs covering literals, chained
     * HOFs, newtypes, matches and maps, exactly TWO of these fire:
     *
     *   field read with no solved type and no declared shape
     *   identifier resolved nowhere and the position expects nothing
     *
     * The rest are reachable in principle and unreached in practice. They stay
     * listed because the gate's job is to catch a NEW place where the compiler
     * gives up, and an unlisted reason firing is exactly that.
     */
    private val allowed = setOf(
        "ability op reference with no solved signature",
        "let binding with no solved type",
        "qualified identifier not in imports",
        "top-level expression with no recorded solve",
        "call with no solved return, no callee arrow and no context",
        "field read with no solved type and no declared shape",
        "identifier resolved nowhere and the position expects nothing",
        "inherent method reference with no solved signature",
        "match with no cases and no context",
        "method call with no solved return, no method arrow and no context",
        "method reference with no solved signature",
        "perform op with no solved result and no context",
        "perform op with no solved return, no resolved return and no context",
        "pipe placeholder with no piped-in type",
        "static ability op field access with no solved signature",
        "static ability op with no solved, declared or context type",
        "variant construct with no solved or context type",
    )

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
        TypeVar.unknownMinted.clear()
    }

    @AfterEach
    fun teardown() = TypeVar.unknownMinted.clear()

    @Test
    fun `no new place where the compiler gives up on a type`() {
        typeChecker.typeCheck(
            parser.parseParsedModule(
                """
                type M = | Get, Post |
                type Rt = { m: M, k: Int }
                let pick = { rts: [Rt] } -> { key } ->
                  match rts is
                    [] -> key
                    [h, ...t] -> key
                let main = {} -> (pick { rts = [ { m = Get, k = 7 } ] }) { key = 7 }
                """.trimIndent(),
                "test.mz",
            ),
            Paths.get("."),
        )
        val seen = TypeVar.unknownMinted.toSet()
        val novel = seen - allowed
        if (novel.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("${novel.size} new place(s) mint an 'unknown' type placeholder:")
                    novel.sorted().forEach { appendLine("  $it") }
                    appendLine()
                    appendLine("A placeholder means the compiler could not determine a type. Adding one")
                    appendLine("is a deliberate act — give it a reason and list it here, or find the type.")
                },
            )
        }
    }
}
