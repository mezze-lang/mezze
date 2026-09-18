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

    /** Every reason the compiler currently mints an unknown placeholder for. */
    private val allowed = setOf(
        "no context type at buildExpr",
        "top-level expression",
        "let binding with no solved type",
        "destructuring let with no solved type",
        // The big one, and the most actionable: a `__native_*` reference has no
        // declared Mezze type, so every one of them lands here. This is BUG 19's
        // native-return hole, counted.
        "identifier resolved nowhere (mostly `__native_*`, whose Mezze type is undeclared)",
        "qualified identifier not in imports",
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
