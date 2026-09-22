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
     * **It was 17. It is 9, and the 9 are exactly the ones that fire.**
     *
     * The previous version of this note said "exactly TWO of these fire",
     * measured over one program, and kept the other fifteen on the grounds that
     * they are "reachable in principle and unreached in practice". Both halves of
     * that were re-measured by counting every mint across `:typechecker:test`,
     * `:interpreter:test`, `:lsp:test` and `:cli:test` — about 920,000 mints in
     * total:
     *
     *   673,749  field read with no solved type and no declared shape
     *   264,516  identifier resolved nowhere and the position expects nothing
     *     1,373  call with no solved return, no callee arrow and no context
     *       635  let binding with no solved type
     *       222  variant construct with no solved or context type
     *        49  perform op with no solved return, no resolved return and no context
     *         6  static ability op field access with no solved signature
     *         4  qualified identifier not in imports
     *         3  ability op reference with no solved signature
     *
     * NINE fire, not two. The other EIGHT minted zero and are gone — each is now
     * a loud failure naming its position (`TypedAstBuilder.unreachedPosition`),
     * the same move this file describes three of the original six having already
     * had. An unreached last resort is not a safety net: a placeholder is
     * indistinguishable from an ordinary type variable once it leaves emit, and
     * downstream reads a type variable as polymorphism and guesses from it.
     *
     * So every entry below is now a place the compiler ACTUALLY gives up, and
     * shrinking the list is the elaboration work. A new reason appearing is a new
     * guess, and should be a deliberate act with a line here.
     */
    private val allowed = setOf(
        "ability op reference with no solved signature",
        "call with no solved return, no callee arrow and no context",
        "field read with no solved type and no declared shape",
        "identifier resolved nowhere and the position expects nothing",
        "let binding with no solved type",
        "perform op with no solved return, no resolved return and no context",
        "qualified identifier not in imports",
        "static ability op field access with no solved signature",
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
