package com.mezze.language.typechecker

import com.mezze.language.ast.MagicParams
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * PERMANENT GUARD (S7.2): no TypeCheckError message may leak an internal
 * artifact.
 *
 * The re-layered typechecker renders every type in an error via the proper
 * [com.mezze.language.ast.prettyPrintType] / prettyPrintTypePair printer, which
 * numbers type variables `'a, 'b, ...` and never truncates. This test drives a
 * corpus of intentionally-erroring programs through the checker and asserts that
 * NO produced message contains:
 *   - a bare `__internal_`-prefixed type-variable name (raw solver var leak), or
 *   - a `MezzeType.toString()` truncation artifact (`<max depth>`,
 *     `<StackOverflowError>`), or
 *   - a raw `MezzeType.toString()` class-constructor form (e.g. `RecordType(`,
 *     `VarType(`, `FunctionType(`) — the printer emits `{ ... }`, `'a -> 'b`,
 *     etc., never the Kotlin class name.
 *
 * If this fails, some error-construction site interpolated a raw type instead of
 * routing it through the printer — route it through prettyPrintType.
 */
class ErrorMessageQualityGuardTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    /** The MezzeType subclass names — their appearance as `Name(` in a message
     *  means a raw `.toString()` leaked in instead of the pretty printer. */
    private val rawTypeCtorForms = listOf(
        "VarType(", "RecordType(", "VariantType(", "FunctionType(",
        "TypeApplication(", "SeqType(", "ListType(", "MapType(",
        "SetType(", "NewtypeType(", "SuspendType(", "EffectRow(",
        "SortedMapType(", "SortedSetType(",
    )

    /** Programs that MUST fail to type-check, spanning the high-traffic error
     *  categories: mismatch, annotation-vs-body, field/var/dispatch not found,
     *  effect mismatch, newtype mismatch, infinite type, callback params. */
    private val erroringPrograms: List<Pair<String, String>> = listOf(
        "annotation-vs-body" to """
            let x: Int = "hello"
            x
        """.trimIndent(),
        "seq-element-mismatch" to """
            let arr = [1, 2, "three"]
            arr
        """.trimIndent(),
        "operator-operand-mismatch" to """
            let bad = 1 + "two"
            bad
        """.trimIndent(),
        "function-arg-field-mismatch" to """
            let add = {x: Int, y: Int} -> x + y
            let r = add {x = 1, y = "nope"}
            r
        """.trimIndent(),
        "undefined-variable" to """
            let test = undefinedVar + 5
            test
        """.trimIndent(),
        "field-not-found" to """
            type Point = { x: Int, y: Int }
            let p: Point = { x = 1, y = 2 }
            let bad = p.z
            bad
        """.trimIndent(),
        "callback-param-names" to """
            let fold = {xs: [Int], init: Int, f: {acc: Int, x: Int} -> Int} -> init
            let r = fold {xs = [1, 2], init = 0, f = {acc, i} -> acc + i}
            r
        """.trimIndent(),
        "nested-record-mismatch" to """
            let f = {p: {inner: {deep: Int}}} -> p
            let r = f {p = {inner = {deep = "no"}}}
            r
        """.trimIndent(),
        "match-branch-mismatch" to """
            type Shape = | Circle { r: Int }, Square { s: Int } |
            let area = {sh: Shape} -> match sh is
                Circle { r } -> r
                Square { s } -> "big"
            area
        """.trimIndent(),
    )

    @Test
    fun `no error message leaks an internal tyvar or a truncated raw type`() {
        val offenders = mutableListOf<String>()
        for ((label, source) in erroringPrograms) {
            val messages = errorMessagesFor(source)
            if (messages.isEmpty()) {
                offenders.add("[$label] expected a type error but the program checked clean")
                continue
            }
            for (message in messages) {
                checkMessage(label, message)?.let { offenders.add(it) }
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "Error messages leaked internal/lossy artifacts:\n" + offenders.joinToString("\n"),
        )
    }

    private fun checkMessage(label: String, message: String): String? {
        if (message.contains(MagicParams.INTERNAL_PREFIX)) {
            return "[$label] message leaks an internal tyvar (${MagicParams.INTERNAL_PREFIX}): $message"
        }
        if (message.contains("<max depth>") || message.contains("<StackOverflowError>")) {
            return "[$label] message contains a MezzeType.toString() truncation artifact: $message"
        }
        rawTypeCtorForms.firstOrNull { message.contains(it) }?.let { form ->
            return "[$label] message contains a raw MezzeType.toString() form ($form): $message"
        }
        return null
    }

    /**
     * ALL error messages the checker produces for [source] — not just the first.
     * We go through the lower-level [TypeChecker.checkParsedModule], which
     * returns the full error list on the failure path; the public
     * [TypeChecker.typeCheck] throws only the first error, so it would hide any
     * lossy rendering in a later error of a multi-error program.
     */
    private fun errorMessagesFor(source: String): List<String> {
        val parsed = parser.parseParsedModule(source, "guard.mz")
        return when (val result = typeChecker.checkParsedModule(parsed, Paths.get("."))) {
            is TypeChecker.TypeCheckResult.Failure -> result.errors.map { it.message }
            is TypeChecker.TypeCheckResult.Success -> emptyList()
        }
    }
}
