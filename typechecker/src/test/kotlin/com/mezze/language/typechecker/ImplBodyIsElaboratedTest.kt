package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The compiler's single largest "I could not determine a type here" position was
 * a METHOD reference filed under a field read.
 *
 * The placeholder census's top entry — 673,749 of about 920,000 mints — reads
 * `field read with no solved type and no declared shape`. Chasing it on the
 * strength of that name is a mistake, and the cases below are what corrected it:
 * the reads it blames are mostly not field reads. They are `x.add {y}`,
 * `v.decode {..}` — methods — whose receiver is an `Int`, a `Float` or a variant,
 * so `declaredShape(receiver)` is not a record, the member is not among its
 * fields, and the give-up fires under a name that sends the next reader looking
 * at records.
 *
 * They arrived there with NO context type. Every sibling branch of the same
 * `when` in `buildMethodCall` passes `solvedFnType ?: contextType ?: <position>`;
 * the one branch for a method nothing resolved passed nothing at all. The fix is
 * to state the arrow the call site already holds — the argument record it just
 * built, to the return the solver recorded for this call.
 *
 * WITHDRAWN, and recorded here so it is not retried: threading `ImplValidator`'s
 * kept solve down to emit as the impl operation's context type. It was built —
 * `ImplOpSolution` given the operation's Loc and solved arrow, carried through
 * `SolvedModule`, read at the one place that mints
 * `VarType(freshInternal("op_<name>"))` — and it connected, 341 hits and 0
 * misses on a stdlib-touching compile. It changed NOTHING observable. Emit
 * reconstructs an operation's arrow from its seeded parameters and its body's own
 * inferred type, and a named ability impl always has a declared signature to seed
 * from, so the solve had nothing left to add. The emitted operation types are
 * already concrete without it, including for `having 'out` (which the checker
 * requires an explicit binding for, so the declaration supplies that too).
 *
 * The lesson generalises: a placeholder REASON names the code path that gave up,
 * not the construct that caused it. Measure which programs actually mint before
 * believing the label.
 */
class ImplBodyIsElaboratedTest {

    private lateinit var parser: MezzeParser

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        TypeVar.unknownMinted.clear()
    }

    @AfterEach
    fun tearDown() = TypeVar.unknownMinted.clear()

    private fun check(src: String) =
        TypeChecker().typeCheck(parser.parseParsedModule(src.trimIndent(), "impl.mz"), Paths.get("."))

    /** Placeholder reasons minted while checking [src]. */
    private fun mintsFor(src: String): List<String> {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        TypeVar.unknownMinted.clear()
        check(src)
        return TypeVar.unknownMinted.toList()
    }

    private fun fieldReadMints(src: String) = mintsFor(src).count { it.contains("field read") }

    /**
     * Impl shapes that DID mint. The simple concrete case
     * (`impl Area for Sq { area = {it} -> it.s }`) does NOT: its `it` is seeded
     * from the declared signature and `declaredShape(Sq)` supplies the field, so
     * a test written on it passes either way. It was, and it proved nothing.
     * Each case below reaches the method-reference position instead.
     */
    private val mintingShapes = listOf(
        "a generic impl target" to """
            ability Pick for 'a where { pick: { it: 'a, k: Int } -> Int }
            type Box 'a = { inner: 'a, n: Int }
            impl Pick for Box 'a where { pick = { it, k } -> it.n + k }
            Pick.pick { it = { inner = 1, n = 2 }, k = 3 }
        """,
        "a match over the receiver's payload" to """
            ability Sum for 'a where { total: { it: 'a } -> Int }
            type Shape = | Circle { r: Int }, Rect { w: Int, h: Int } |
            impl Sum for Shape where {
              total = { it } -> match it is
                Circle { r } -> r
                Rect { w, h } -> w * h
            }
            Sum.total { it = Circle { r = 2 } }
        """,
        "an impl body calling another ability" to """
            ability Area for 'a where { area: { it: 'a } -> Int }
            ability Show for 'a where { show: { it: 'a } -> Int }
            type Sq = { s: Int }
            impl Area for Sq where { area = { it } -> it.s * it.s }
            impl Show for Sq where { show = { it } -> Area.area { it = it } }
            Show.show { it = { s = 3 } }
        """,
        "a lambda inside an impl body" to """
            ability Fold for 'a where { fold: { it: 'a } -> Int }
            type Pair = { a: Int, b: Int }
            impl Fold for Pair where {
              fold = { it } -> do
                let f = { x } -> x + it.a
                f { x = it.b }
            }
            Fold.fold { it = { a = 1, b = 2 } }
        """,
    )

    @Test
    fun `a method call nothing resolved does not give up on the callee type`() {
        // Each of these minted exactly one `field read ...` placeholder, and in
        // every case the "field" was `add` on an Int — the arithmetic method.
        for ((label, src) in mintingShapes) {
            assertEquals(
                0, fieldReadMints(src),
                "$label: the compiler gave up on a type here and blamed a field read. " +
                    "The read is a METHOD reference; its arrow is the just-built argument " +
                    "record to this call's solved return, both of which emit already holds.",
            )
        }
    }

    @Test
    fun `an ordinary function was never the problem`() {
        // The control for the above: if this ever mints, the probe is measuring
        // something other than what it claims.
        assertEquals(
            0,
            fieldReadMints(
                """
                type Sq = { s: Int }
                let area = { p: Sq } -> p.s
                area { p = { s = 3 } }
                """,
            ),
        )
    }

    @Test
    fun `the impls still produce the right answers`() {
        // Typing a position is worth nothing if it changes what the program
        // means. The placeholder path was SOUND, just uninformative — so the
        // guard is that these still compile to the same types, not that they
        // newly work.
        assertEquals(
            MezzeType.IntType,
            check(
                """
                ability Area for 'a where { area: { it: 'a } -> Int }
                type Sq = { s: Int }
                impl Area for Sq where { area = { it } -> it.s * it.s }
                let a = Area.area { it = { s = 4 } }
                a
                """,
            ).getVariableType("a"),
        )
        assertEquals(
            MezzeType.IntType,
            check(
                """
                ability Sum for 'a where { total: { it: 'a } -> Int }
                type Shape = | Circle { r: Int }, Rect { w: Int, h: Int } |
                impl Sum for Shape where {
                  total = { it } -> match it is
                    Circle { r } -> r
                    Rect { w, h } -> w * h
                }
                let t = Sum.total { it = Rect { w = 2, h = 3 } }
                t
                """,
            ).getVariableType("t"),
        )
    }
}
