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
     * RE-MEASURED on `:typechecker:test` alone (so these are NOT comparable to
     * the four-module figures above — only to each other), before and after the
     * method-reference fix in [ImplBodyIsElaboratedTest]:
     *
     *                                                          before    after
     *   field read with no solved type and no declared shape    59,971    5,900
     *   identifier resolved nowhere ...                         31,229   31,229
     *   let binding with no solved type                            528      528
     *   call with no solved return ...                             124      124
     *   variant construct with no solved or context type            72       77
     *   perform op with no solved return ...                        27       27
     *                                                 TOTAL     91,951   37,885
     *
     * TWO things in that table matter more than the totals.
     *
     * The first: a reason names the CODE PATH that gave up, not the construct
     * that caused it. The overwhelming majority of "field read" was `x.add {y}`
     * and `v.decode {..}` — METHOD references on an Int, a Float or a variant,
     * reaching the member-access arm with no context type. Nobody reading this
     * list would have guessed that, and the name sent two attempts looking at
     * records and impl bodies. Measure which programs mint before believing a
     * label.
     *
     * The second: `variant construct` went UP, 72 → 77. Supplying a type where
     * there was none moves some positions rather than removing them, so a drop
     * in the total is not by itself evidence that every position improved.
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

    /**
     * The programs the gate is measured over.
     *
     * This used to be ONE program — a seq match over a record — so the gate
     * guarded the reason list against whatever that single source happened to
     * reach. A new give-up position in abilities, effects, codecs or newtypes
     * would have been invisible to it while the list still read as authoritative.
     * Each entry below is here to reach a different family of positions; add one
     * when a family is not represented, not when a bug is found.
     *
     * MEASURED coverage of the current set: these programs reach THREE of the
     * nine listed reasons — the field read, the unresolved identifier, and the
     * variant construct. The gate is therefore a partial guard, not a proof: a
     * new give-up in one of the other six families still slips past it. Closing
     * that means adding a program that reaches the family, which is why the list
     * is programs rather than one source.
     */
    private val programs: List<Pair<String, String>> = listOf(
        "seq match over a record" to """
            type M = | Get, Post |
            type Rt = { m: M, k: Int }
            let pick = { rts: [Rt] } -> { key } ->
              match rts is
                [] -> key
                [h, ...t] -> key
            let main = {} -> (pick { rts = [ { m = Get, k = 7 } ] }) { key = 7 }
        """,
        // Ability declaration, impl and a method call — reaches the two
        // "no solved signature" positions and the static-op field access.
        "ability with an impl and a method call" to """
            ability Describe for 'a having 'out where {
              describe: { it: 'a } -> 'out
            }
            impl Describe for Int having 'out = Str where {
              describe = { it } -> "n"
            }
            let main = {} -> Describe.describe { it = 1 }
        """,
        // Effect declaration, performer and a perform — reaches the perform-op
        // position and the associated-type binding path.
        "effect with a performer and a perform" to """
            effect Ask having 'v where {
              ask: {} -> 'v <Ask>
            }
            impl Ask for Fixed having 'v = Int where {
              ask = {} -> 7
            }
            let main = {} -> perform Fixed in Ask.ask {}
        """,
        // Newtype construction and destructuring — a nominal wrapper is where a
        // "declared shape" is available and a placeholder should NOT be minted.
        "newtype construct and open" to """
            newtype Email = Str
            let make = { s: Str } -> Email.(s)
            let open = { e: Email } -> match e is Email.(inner) -> inner
            let main = {} -> open { e = make { s = "a" } }
        """,
        // Polymorphic higher-order code with an unannotated lambda — the shape
        // that most often leaves a call with no solved return.
        "higher-order with unannotated lambdas" to """
            let twice = { f, x } -> f { x = f { x = x } }
            let main = {} -> twice { f = { x } -> x + 1, x = 1 }
        """,
    )

    @Test
    fun `no new place where the compiler gives up on a type`() {
        for ((label, src) in programs) {
            ModuleResolver.clearCache()
            TypeChecker.clearCache()
            val tc = TypeChecker()
            try {
                tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "gate.mz"), Paths.get("."))
            } catch (e: Throwable) {
                // A program that fails to typecheck still reports whatever it
                // minted on the way, which is the thing being measured. It must
                // not abort the sweep — but a program meant to COMPILE that stops
                // compiling is worth knowing about, so say so rather than swallow.
                fail("gate program '$label' no longer typechecks: ${e.message}")
            }
        }
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
