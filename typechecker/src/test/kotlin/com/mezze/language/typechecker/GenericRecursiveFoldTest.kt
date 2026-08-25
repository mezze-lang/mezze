package com.mezze.language.typechecker

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.ast.prettyPrintTypeWithAliases
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Probe for inference on a recursive generic fold over a parameterised
 * variant tree. Verifies:
 *   (a) `fold` is inferred polymorphic in BOTH the element type and the
 *       accumulator type — they should not collapse onto the same TyVar
 *       through recursion or shared-default landmines.
 *   (b) `sum` instantiates `fold` correctly: `'a = Int`, `'b = Int`, and
 *       the returned function is monomorphic at use site.
 *
 * Mirrors the user-reported shape exactly.
 */
class GenericRecursiveFoldTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun `recursive generic fold over Tree — polymorphic in element and accumulator`(@TempDir tempDir: Path) {
        val source = """
            type Tree 'a = |
              Leaf,
              Node { value: 'a, left: Tree 'a, right: Tree 'a },
            |

            let fold = { tree, acc, f } -> match tree is
              Leaf -> acc
              Node { value, left, right } -> fold {
                tree = right,
                acc = f { acc = fold { tree = left, acc, f }, val = value },
                f,
              }

            let sum = { tree: Tree Int } -> fold { tree, acc = 0, f = { acc, val } -> acc + val }
        """.trimIndent()

        val parsed = parser.parseParsedModule(source, "test.mz")
        val typed = typeChecker.typeCheck(parsed, tempDir)

        val foldType = typed.getVariableType("fold")
        val sumType = typed.getVariableType("sum")

        val foldStr = foldType?.let { prettyPrintTypeWithAliases(it, typeChecker.getTypeAliases()) } ?: "null"
        val sumStr = sumType?.let { prettyPrintTypeWithAliases(it, typeChecker.getTypeAliases()) } ?: "null"

        println("[Tree-fold] fold : $foldStr")
        println("[Tree-fold] sum  : $sumStr")

        // Inspect the raw Kotlin tree to see whether `tree`'s slot is
        // a TypeApplication (good — keeps the alias) or a bare VariantType
        // (bad — inlined).
        fun describe(t: com.mezze.language.ast.MezzeType?, indent: String = "  "): String {
            return when (t) {
                null -> "null"
                is com.mezze.language.ast.MezzeType.FunctionType ->
                    "FunctionType\n${indent}param=${describe(t.paramType, "$indent  ")}\n${indent}return=${describe(t.returnType, "$indent  ")}"
                is com.mezze.language.ast.MezzeType.RecordType ->
                    "RecordType(typeName=${t.typeName})\n" +
                    t.fields.entries.joinToString("\n") { "$indent${it.key}=${describe(it.value, "$indent  ")}" }
                is com.mezze.language.ast.MezzeType.VariantType ->
                    "VariantType(typeName=${t.typeName}, ctors=${t.constructors.keys})"
                is com.mezze.language.ast.MezzeType.TypeApplication ->
                    "TypeApplication(${t.constructor}, args=${t.args.size})"
                else -> "${t.javaClass.simpleName}: $t"
            }
        }
        println("[Tree-fold-raw] sum=\n${describe(sumType)}")

        assertNotNull(foldType, "fold did not typecheck")
        assertNotNull(sumType, "sum did not typecheck")

        // Expectations (without locking exact TyVar names):
        //
        // fold : { tree: Tree 'a, acc: 'b, f: { acc: 'b, val: 'a } -> 'b } -> 'b
        // sum  : { tree: Tree Int } -> Int
        //
        // `sum`'s `tree` is annotated `Tree Int` to pin the element type. Under
        // heterogeneous `+` (NumAdd 'r for 'l having 'out), `acc + val` no longer
        // forces the accumulator and element onto the same type — with `acc = 0`
        // fixing the left operand and the result to Int, the right operand `val`
        // is left ambiguous (Int+Int and Int+Nat both give Int), so an
        // unannotated `sum` would infer the more general `Tree 'a` element.
        // Pinning `tree: Tree Int` keeps this test focused on alias preservation
        // and the fold's element/accumulator non-collapse.

        // sum's `tree` should print as the in-scope alias `Tree Int`,
        // NOT the expanded inline variant. Same for `fold` — `tree`
        // should be `Tree 'b` (or whichever TyVar is the element).
        // The recursive `left` and `right` already print as `Tree 'b`
        // correctly; the bug is that the OUTER `tree:` slot loses its
        // nominal name and inlines the variant.
        assertTrue(sumStr.contains("Tree Int"),
            "sum's tree param should print as the alias `Tree Int`, not " +
            "the expanded variant. Got: $sumStr")
        assertFalse(sumStr.contains("| Leaf"),
            "sum's tree should NOT print as the expanded variant; the " +
            "Tree alias is in scope and should be used. Got: $sumStr")
        // sum's return type — match the literal text or the printed alias.
        assertTrue(Regex("->\\s*Int\\b").containsMatchIn(sumStr),
            "sum should return Int, got: $sumStr")

        // fold must mention Tree (parameterised) and have a callback with
        // both `acc` and `val` fields. It must NOT have collapsed `'a`
        // and `'b` to the same TyVar — check that `acc` and `val` field
        // types in the callback are independent TyVars (different names).
        assertTrue(foldStr.contains("Tree"),
            "fold's tree param should mention Tree, got: $foldStr")
        assertFalse(foldStr.contains("| Leaf"),
            "fold's tree should NOT print as the expanded variant; the " +
            "Tree alias is in scope and should be used. Got: $foldStr")
        assertTrue(foldStr.contains("acc:") && foldStr.contains("val:"),
            "fold's callback should bind both acc and val, got: $foldStr")

        // Extract every printed `'<name>` TyVar reference; they must
        // come in at least TWO distinct names (accumulator vs element).
        // If everything collapsed to one TyVar — the bug pattern from
        // the user-reported LSP output — there'd only be `'a`.
        val tyVars = Regex("'[a-z][a-z0-9]*").findAll(foldStr).map { it.value }.toSet()
        assertTrue(tyVars.size >= 2,
            "fold should have at least TWO distinct TyVars (accumulator and " +
            "element). Found only: $tyVars in: $foldStr")

        // Specifically: the callback's `acc:` and the outer record's
        // `acc:` must use the SAME TyVar (the accumulator); the
        // callback's `val:` (and the Node's `value:`) must use a
        // DIFFERENT TyVar (the element). Match both halves and assert.
        val outerAcc = Regex("acc:\\s*('[a-z][a-z0-9]*)\\s*,\\s*f:").find(foldStr)
            ?.destructured?.component1()
        val callbackAcc = Regex("f:\\s*\\{\\s*acc:\\s*('[a-z][a-z0-9]*)").find(foldStr)
            ?.destructured?.component1()
        val callbackVal = Regex("val:\\s*('[a-z][a-z0-9]*)").find(foldStr)
            ?.destructured?.component1()
        val nodeValue = Regex("value:\\s*('[a-z][a-z0-9]*)").find(foldStr)
            ?.destructured?.component1()

        if (outerAcc != null && callbackAcc != null) {
            assertEquals(outerAcc, callbackAcc,
                "fold's outer `acc` and the callback's `acc` should share the " +
                "SAME accumulator TyVar; got $outerAcc vs $callbackAcc in: $foldStr")
        }
        if (callbackVal != null && nodeValue != null) {
            assertEquals(nodeValue, callbackVal,
                "fold's callback `val` and the Node's `value` should share the " +
                "SAME element TyVar; got $nodeValue vs $callbackVal in: $foldStr")
        }
        if (outerAcc != null && callbackVal != null) {
            assertNotEquals(outerAcc, callbackVal,
                "fold's accumulator TyVar and element TyVar should be DISTINCT; " +
                "both came out as $outerAcc in: $foldStr  " +
                "(this is the bug pattern reported in the LSP build).")
        }
    }
}
