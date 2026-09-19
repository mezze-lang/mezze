package com.mezze.language.langservice.completion

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.parsedast.Loc
import com.mezze.language.parsedast.ParsedFunctionType
import com.mezze.language.parsedast.ParsedNamedType
import com.mezze.language.parsedast.ParsedRecordType
import com.mezze.language.parsedast.ParsedRecordTypeEntry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Direct tests for the snippet-building helpers used by dot-completion
 * (`xs.|` → `xs.method { p1 = $1, p2 = $2 }`). They call the CompletionEngine
 * helpers directly (no LSP harness, no `stdlib.sqlite` on the classpath).
 *
 * Covers:
 *   - Plain field hint → `${idx:name}` placeholder.
 *   - Function-typed field hint (callback) → `${idx:{innerParams} -> $0}`.
 *   - `paramHintsFromMezzeType` reads inner record param names from the
 *     callback's typed paramType.
 *   - `paramHintsFromAnnotation` reads inner record entries from the
 *     callback's parsed paramType.
 */
class MethodSnippetTest {

    private val loc = Loc("test", 1, 1, 1, 1)

    @Test
    fun `buildMethodSnippet emits empty braces for no params`() {
        val s = CompletionEngine.buildMethodSnippet("size", emptyList())
        assertEquals("size {}", s)
    }

    @Test
    fun `buildMethodSnippet emits plain placeholders for non-callback hints`() {
        val s = CompletionEngine.buildMethodSnippet(
            "add",
            listOf(CompletionEngine.MethodParamHint.Plain("r"))
        )
        assertEquals("add {r = \${1:r}}", s)
    }

    @Test
    fun `buildMethodSnippet emits lambda skeleton for callback hints`() {
        val s = CompletionEngine.buildMethodSnippet(
            "any",
            listOf(CompletionEngine.MethodParamHint.Callback("p", listOf("x")))
        )
        // Selecting `any` should drop a real lambda skeleton with the
        // predicate's param name `x`.
        assertEquals("any {p = \${1:{x} -> \$0}}", s)
    }

    @Test
    fun `paramHintsFromMezzeType marks function-typed fields as Callback with inner names`() {
        // Mirror Seq.fold's signature without the receiver (it stripped):
        //   { acc: 'b, f: { acc: 'b, x: 'a } -> 'b }
        val acc = MezzeType.VarType(TypeVar.fresh())
        val x = MezzeType.VarType(TypeVar.fresh())
        val callbackParam = MezzeType.RecordType(
            fields = mapOf("acc" to acc, "x" to x),
            rowVar = null
        )
        val callback = MezzeType.FunctionType(
            paramType = callbackParam,
            returnType = acc
        )
        val record = MezzeType.RecordType(
            fields = mapOf("it" to MezzeType.TypeApplication("Seq", listOf(x)), "acc" to acc, "f" to callback),
            rowVar = null
        )

        val hints = CompletionEngine.paramHintsFromMezzeType(record, stripIt = true)
        // `it` stripped, leaves [acc, f]
        val byName = hints.associateBy { it.name }
        assertEquals(2, hints.size)
        assertTrue(
            byName["acc"] is CompletionEngine.MethodParamHint.Plain,
            "acc is a value param, must be Plain"
        )
        val fHint = byName["f"]
        assertTrue(
            fHint is CompletionEngine.MethodParamHint.Callback,
            "f is a function param, must be Callback"
        )
        val cb = fHint as CompletionEngine.MethodParamHint.Callback
        assertEquals(
            listOf("acc", "x"), cb.callbackParams,
            "callbackParams should mirror the inner record's field names"
        )
    }

    @Test
    fun `paramHintsFromAnnotation marks function-typed fields as Callback`() {
        // { it: ['a], p: { x: 'a } -> Bool } — like Seq.any's annotation.
        val ann = ParsedRecordType(
            entries = listOf(
                ParsedRecordTypeEntry("it", ParsedNamedType("Seq", loc), loc),
                ParsedRecordTypeEntry(
                    "p",
                    ParsedFunctionType(
                        paramType = ParsedRecordType(
                            entries = listOf(
                                ParsedRecordTypeEntry("x", ParsedNamedType("'a", loc), loc)
                            ),
                            loc = loc
                        ),
                        returnType = ParsedNamedType("Bool", loc),
                        loc = loc
                    ),
                    loc
                )
            ),
            loc = loc
        )

        val hints = CompletionEngine.paramHintsFromAnnotation(ann, stripIt = true)
        assertEquals(1, hints.size)
        val p = hints.first()
        assertTrue(
            p is CompletionEngine.MethodParamHint.Callback,
            "predicate `p: { x: 'a } -> Bool` must be Callback"
        )
        assertEquals("p", p.name)
        assertEquals(listOf("x"), (p as CompletionEngine.MethodParamHint.Callback).callbackParams)
    }

    @Test
    fun `buildMethodSnippet rendering matches expected for a Seq fold like signature`() {
        // Combining the helpers end-to-end: `xs.fold` should produce
        //   fold {acc = ${1:acc}, f = ${2:{acc, x} -> $0}}
        val s = CompletionEngine.buildMethodSnippet(
            "fold",
            listOf(
                CompletionEngine.MethodParamHint.Plain("acc"),
                CompletionEngine.MethodParamHint.Callback("f", listOf("acc", "x"))
            )
        )
        assertEquals("fold {acc = \${1:acc}, f = \${2:{acc, x} -> \$0}}", s)
    }
}
