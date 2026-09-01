package com.mezze.language.typechecker

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.lookup.TypedAstLookup
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * #392: calling a method on a receiver that was bound by a MATCH-ARM PATTERN
 * must dispatch the same way as a receiver bound by `let` or a param.
 *
 * Root cause: the do-block `let` path runs a local incremental solve so the
 * binding's stored (name-keyed) type is already concrete when
 * getReceiverConcreteType reads it; a match-arm binding was extended into the
 * case context with the RAW fresh pattern VarType (no local solve), so the
 * receiver's type was unavailable at method-resolve time and the call fell
 * through to a structural field access ("record fields don't match").
 */
class MatchBoundReceiverMethodDispatchTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun check(src: String): TypeChecker.TypeCheckResult {
        val parsed = parser.parseParsedModule(src, "match_recv.mz")
        return typeChecker.checkParsedModule(parsed, Paths.get("."), null, true)
    }

    private fun assertChecks(src: String): TypeChecker.TypeCheckResult.Success {
        val r = check(src)
        assertTrue(
            r is TypeChecker.TypeCheckResult.Success,
            "expected it to typecheck; got: " + (r as? TypeChecker.TypeCheckResult.Failure)
                ?.errors?.joinToString("\n") { it.message },
        )
        return r as TypeChecker.TypeCheckResult.Success
    }

    private fun bindingTypeAt(success: TypeChecker.TypeCheckResult.Success, line: Int, col: Int): String {
        val tm = success.typedModule ?: fail("no typed module")
        return when (val hit = TypedAstLookup.findAtPosition(tm, line, col)) {
            is TypedAstLookup.LookupResult.Binding -> prettyPrintType(hit.type)
            is TypedAstLookup.LookupResult.Expr -> prettyPrintType(hit.expr.type)
            else -> fail("nothing found at $line:$col")
        }
    }

    @Test
    fun `inherent method on a match-arm-bound receiver dispatches`() {
        // The receiver `inner` is itself bound by a nested match pattern.
        // Previously: "record fields don't match: missing 'i'" at inner.get {}.
        val src = """
            type Box 'a = | Box { val: 'a } |
            impl Box 'a where { get = { it } -> match it is Box { val = v } -> v }

            let via_match = { ob: Option (Box Int) } -> match ob is
              Some { val = inner } -> inner.get {}
              None -> 0
        """.trimIndent()

        assertChecks(src)
    }

    @Test
    fun `inherent method on a single-ctor match-arm-bound receiver dispatches`() {
        val src = """
            type Box 'a = | Box { val: 'a } |
            type Wrap 'a = | Wrap { inner: 'a } |
            impl Box 'a where { get = { it } -> match it is Box { val = v } -> v }

            let c1 = { w: Wrap (Box Int) } -> match w is
              Wrap { inner = b } -> b.get {}
        """.trimIndent()

        assertChecks(src)
    }

    @Test
    fun `match-arm-bound receiver dispatch unifies arms to a concrete return`() {
        // The `Some` arm dispatches `inner.get {}` and its result must unify with
        // the `None -> 0` arm (Int). Before #392 this failed with "record fields
        // don't match" / "expected Option Int, but got Int" because the receiver's
        // solved type never reached method resolution and the whole match's return
        // was corrupted. Here we assert the enclosing function's inferred return.
        val src = """
            type Box 'a = | Box { val: 'a } |
            impl Box 'a where { get = { it } -> match it is Box { val = v } -> v }

            let c: { ob: Option (Box Int) } -> Int = { ob } -> match ob is
              Some { val = inner } -> inner.get {}
              None -> 0
        """.trimIndent()

        assertChecks(src)
    }

    @Test
    fun `ability method on a match-arm-bound receiver dispatches`() {
        val src = """
            ability Greet for 'a where { greet: { it: 'a } -> Str }
            type Dog = | Dog { name: Str } |
            impl Greet for Dog where { greet = { it } -> match it is Dog { name = n } -> n }

            let via_match = { ob: Option Dog } -> match ob is
              Some { val = d } -> d.greet {}
              None -> "none"
        """.trimIndent()

        assertChecks(src)
    }
}
