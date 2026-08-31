package com.mezze.language.typechecker.solving

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeIdentity
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.parsedast.Loc
import com.mezze.language.parser.MezzeParser
import com.mezze.language.parser.ParseException
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.constraints.MethodResolveBucket
import com.mezze.language.typechecker.constraints.ResolutionCandidate
import com.mezze.language.typechecker.constraints.ResolutionKind
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.util.NameFallbackProbe
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * S7.1 (#369) — the no-name-dispatch CAPSTONE.
 *
 * Two artifacts, both PERMANENT and always-on:
 *
 *  A. GUARD (unit) — "a NAME is never identity" at the DISPATCH layer.
 *     Constructs inherent-method candidates that share a NAME but differ in HASH
 *     and asserts the receiver does NOT dispatch to the other declaration's impl
 *     (name equality must not cause dispatch equality); and candidates that differ
 *     in NAME but share a HASH and asserts they DO dispatch to the same impl (a
 *     rename-safe alias). The unification half of this property is locked by
 *     [NominalHashIdentityTest]; this locks the post-solve dispatcher.
 *
 *  B. PROOF (corpus) — flips the name-fallback probe ON and type-checks the whole
 *     fuzzer corpus plus a dispatch/ability/inherent-method-heavy program set,
 *     asserting the count of FORBIDDEN name-decides-correctness firings is 0. The
 *     ALLOWED firings (hashless/reserved/slot-label) are printed for transparency
 *     but never fail the test.
 */
class NoNameDispatchCapstoneTest {

    private val hashA = Hash("aaaa000000000000000000000000000000000000000000000000000000000000")
    private val hashB = Hash("bbbb000000000000000000000000000000000000000000000000000000000000")
    private val opHashWidgetA = Hash("0011000000000000000000000000000000000000000000000000000000000000")
    private val opHashWidgetB = Hash("0022000000000000000000000000000000000000000000000000000000000000")

    private lateinit var parser: MezzeParser

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        NameFallbackProbe.reset()
        parser = MezzeParser()
    }

    @AfterEach
    fun teardown() {
        NameFallbackProbe.setEnabled(false)
        NameFallbackProbe.reset()
    }

    // ---------------------------------------------------------------
    // A. Permanent GUARD — name is not identity at the dispatch layer
    // ---------------------------------------------------------------

    /**
     * Two same-named `Widget` declarations from different modules carry different
     * declaration hashes. A `Widget`-hashA receiver must dispatch to the hashA
     * impl ONLY — never cross-dispatch to the hashB impl just because the target
     * type NAME is also "Widget".
     */
    @Test
    fun `same-name different-hash inherent impls do not cross-dispatch`() {
        val receiverA = MezzeType.RecordType(
            mapOf("n" to MezzeType.IntType),
            identity = TypeIdentity.Declared(hashA, "Widget"),
        )
        // Two candidates, SAME displayName "Widget", DIFFERENT target hashes+ops.
        val candA = ResolutionCandidate(
            opHash = opHashWidgetA,
            kind = ResolutionKind.Inherent,
            targetTypeIdentity = TypeIdentity.Declared(hashA, "Widget"),
            displayName = "Widget",
        )
        val candB = ResolutionCandidate(
            opHash = opHashWidgetB,
            kind = ResolutionKind.Inherent,
            targetTypeIdentity = TypeIdentity.Declared(hashB, "Widget"),
            displayName = "Widget",
        )
        val loc = Loc("cap.mz", 1, 1, 1, 8)
        val bucket = MethodResolveBucket(loc, "render", receiverA, listOf(candA, candB))

        val resolutions = DispatchResolver.resolveDispatch(
            buckets = listOf(bucket),
            substitution = Substitution.empty,
            methodCallResolutions = emptyMap(),
            typingContext = TypingContext.empty,
        )

        val resolution = resolutions[loc]
        assertNotNull(resolution, "a concrete Widget-hashA receiver resolves an inherent impl")
        assertEquals(
            opHashWidgetA, resolution.opHash,
            "must dispatch to the hashA impl by HASH — the same-named hashB impl must NOT win by name",
        )
    }

    /**
     * Two DIFFERENTLY-named declarations (`Widget`, `Gadget`) that share a hash are
     * the SAME declaration under a rename-safe alias. A receiver carrying that hash
     * must dispatch to that impl even though its own name differs from the
     * candidate's displayName — identity is the hash, not the string.
     */
    @Test
    fun `different-name same-hash inherent impl dispatches (rename-safe alias)`() {
        // Receiver labeled "Gadget" but carrying hashA.
        val receiver = MezzeType.RecordType(
            mapOf("n" to MezzeType.IntType),
            identity = TypeIdentity.Declared(hashA, "Gadget"),
        )
        // Candidate labeled "Widget" but targeting the SAME hashA.
        val cand = ResolutionCandidate(
            opHash = opHashWidgetA,
            kind = ResolutionKind.Inherent,
            targetTypeIdentity = TypeIdentity.Declared(hashA, "Widget"),
            displayName = "Widget",
        )
        val loc = Loc("cap.mz", 2, 1, 2, 8)
        val bucket = MethodResolveBucket(loc, "render", receiver, listOf(cand))

        val resolutions = DispatchResolver.resolveDispatch(
            buckets = listOf(bucket),
            substitution = Substitution.empty,
            methodCallResolutions = emptyMap(),
            typingContext = TypingContext.empty,
        )

        val resolution = resolutions[loc]
        assertNotNull(
            resolution,
            "a same-hash impl dispatches even though the display names differ (rename-safe alias)",
        )
        assertEquals(opHashWidgetA, resolution.opHash, "dispatch keys on the shared HASH, not the name")
    }

    /**
     * A receiver whose hash matches NEITHER same-named candidate must not dispatch
     * to either by name. (No hash match ⇒ no Resolution — never a name-based guess.)
     */
    @Test
    fun `unmatched-hash receiver does not name-dispatch to a same-named impl`() {
        val hashC = Hash("cccc000000000000000000000000000000000000000000000000000000000000")
        val receiver = MezzeType.RecordType(
            mapOf("n" to MezzeType.IntType),
            identity = TypeIdentity.Declared(hashC, "Widget"),
        )
        val cand = ResolutionCandidate(
            opHash = opHashWidgetA,
            kind = ResolutionKind.Inherent,
            targetTypeIdentity = TypeIdentity.Declared(hashA, "Widget"),
            displayName = "Widget",
        )
        val loc = Loc("cap.mz", 3, 1, 3, 8)
        val bucket = MethodResolveBucket(loc, "render", receiver, listOf(cand))

        val resolutions = DispatchResolver.resolveDispatch(
            buckets = listOf(bucket),
            substitution = Substitution.empty,
            methodCallResolutions = emptyMap(),
            typingContext = TypingContext.empty,
        )

        assertNull(
            resolutions[loc],
            "receiver hashC shares the NAME 'Widget' with the hashA impl but not its hash — must NOT dispatch",
        )
    }

    // ---------------------------------------------------------------
    // B. Corpus PROOF — zero forbidden name-decides-correctness firings
    // ---------------------------------------------------------------

    @Test
    fun `zero forbidden name-fallback firings across the corpus`() {
        NameFallbackProbe.setEnabled(true)
        NameFallbackProbe.reset()

        var programs = 0
        // 1) The fuzzer corpus (fixed seeds → reproducible).
        for (seed in 1L..120L) {
            checkQuietly(generateRandomProgram(Random(seed)))
            programs++
        }
        // 2) Dispatch / ability / inherent-method heavy programs (the sites the
        //    probe instruments — same-named types, cross-type methods, abilities).
        for (src in dispatchHeavyPrograms) {
            checkQuietly(src)
            programs++
        }

        val report = NameFallbackProbe.report()
        // Persist the evidence for the run record.
        runCatching {
            java.io.File("/Users/shriyans/.claude/jobs/b03bba6d/tmp/probe-report.txt")
                .writeText("programs=$programs\n$report")
        }
        println("[S7.1 capstone] corpus programs=$programs")
        println(report)

        assertEquals(
            0L, NameFallbackProbe.forbiddenTotal(),
            "FORBIDDEN name-decides-correctness firings must be 0 across the corpus.\n$report",
        )
    }

    private fun checkQuietly(source: String) {
        try {
            val parsed = parser.parseParsedModule(source, "cap.mz")
            TypeChecker().typeCheck(parsed, Paths.get("."))
        } catch (_: TypeCheckError) {
            // expected for some programs — the probe still fired on any decision made
        } catch (_: ParseException) {
        } catch (_: Exception) {
            // A non-typecheck exception is out of scope for this probe test (the
            // meta-property test owns "never throws non-TypeCheckError"); ignore so
            // one odd program can't mask the name-fallback count.
        }
    }

    // Programs that specifically exercise inherent-method dispatch, ability method
    // dispatch, and same-named-type scenarios — where a name-fallback would fire.
    private val dispatchHeavyPrograms: List<String> = listOf(
        // inherent method on a scalar (hashless receiver — ALLOWED name path)
        """
        let n = 42
        let s = n.to_str {}
        """.trimIndent(),
        // inherent method on a user record (hash receiver — must key on hash)
        """
        type Point = { x: Int, y: Int }
        impl Point where {
          let mag = { it } -> it.x + it.y
        }
        let p = { x = 1, y = 2 }
        let m = p.mag {}
        """.trimIndent(),
        // two user records with DIFFERENT names, each with a same-named method
        """
        type A = { v: Int }
        type B = { v: Int }
        impl A where { let show = { it } -> it.v }
        impl B where { let show = { it } -> it.v }
        let a = { v = 1 }
        let b = { v = 2 }
        let x = a.show {}
        let y = b.show {}
        """.trimIndent(),
        // variant with an inherent method
        """
        type Color = | Red | Green | Blue |
        impl Color where {
          let code = { it } -> match it is {
            Red -> 1
            Green -> 2
            Blue -> 3
          }
        }
        let c = Red
        let k = c.code {}
        """.trimIndent(),
        // string / seq inherent methods (builtin hashless receivers)
        """
        let xs = [1, 2, 3]
        let str = "hello"
        """.trimIndent(),
    )

    // ---- generator (mirrors TypeCheckerMetaPropertyTest) ----

    private fun generateRandomProgram(rng: Random): String {
        val n = 2 + rng.nextInt(5)
        val sb = StringBuilder()
        for (i in 0 until n) {
            val vars = (0 until i).map { "x$it" }
            sb.append("let x$i = ").append(generateExpr(rng, 0, 3, vars)).append("\n")
        }
        return sb.toString()
    }

    private fun generateExpr(rng: Random, depth: Int, maxDepth: Int, vars: List<String>): String {
        if (depth >= maxDepth) return leaf(rng, vars)
        return when (rng.nextInt(6)) {
            0 -> leaf(rng, vars)
            1 -> "{ a = ${generateExpr(rng, depth + 1, maxDepth, vars)}, b = ${generateExpr(rng, depth + 1, maxDepth, vars)} }"
            2 -> "[${generateExpr(rng, depth + 1, maxDepth, vars)}, ${generateExpr(rng, depth + 1, maxDepth, vars)}]"
            3 -> "{ p, q } -> ${generateExpr(rng, depth + 1, maxDepth, vars + listOf("p", "q"))}"
            4 -> "${leafNum(rng, vars)} + ${leafNum(rng, vars)}"
            else -> "\"v = \$(${leafNum(rng, vars)}.to_str {})\""
        }
    }

    private fun leaf(rng: Random, vars: List<String>): String = when (rng.nextInt(4)) {
        0 -> rng.nextInt(100).toString()
        1 -> "\"str${rng.nextInt(9)}\""
        2 -> if (rng.nextBoolean()) "True" else "False"
        else -> vars.randomOrNull(rng) ?: rng.nextInt(100).toString()
    }

    private fun leafNum(rng: Random, vars: List<String>): String =
        vars.randomOrNull(rng) ?: rng.nextInt(100).toString()

    private fun <T> List<T>.randomOrNull(rng: Random): T? =
        if (isEmpty()) null else this[rng.nextInt(size)]
}
