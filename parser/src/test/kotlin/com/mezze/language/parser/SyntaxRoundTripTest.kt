package com.mezze.language.parser

import com.mezze.language.comptime.HostValue
import com.mezze.language.comptime.SyntaxDecoder
import com.mezze.language.comptime.SyntaxEncoder
import com.mezze.language.parsedast.*
import com.mezze.language.parser.internal.QuoteBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * `std::syntax` data is the parser's tree, losslessly: every declaration the
 * parser reads, encoded by [SyntaxEncoder] and decoded by [SyntaxDecoder], is
 * the declaration it was.
 *
 * What the encoding legitimately does not carry is erased on BOTH sides by
 * [normalize], and nothing else:
 * - a `#{…}` block's own Loc. The block is carried as JSON text, which has no
 *   position, so the decoder places it at its owner's Loc. (No statement- or
 *   arm-level `#{…}` occurs in the corpus; the normalizer reaches declarations,
 *   record fields and constructors, and a block anywhere else would fail here
 *   and be named.)
 */
class SyntaxRoundTripTest {

    /** The span's Loc, exactly: an absent `file` is the encoder's spelling of "". */
    private val locate: (HostValue) -> Loc = { v ->
        val f = (v as HostValue.Record).fields.toMap()
        fun int(k: String) = (f.getValue(k) as HostValue.IntV).value.toInt()
        val file = ((f.getValue("file") as HostValue.Opt).value as HostValue.Str?)?.value ?: ""
        Loc(file, int("start_line"), int("start_col"), int("end_line"), int("end_col"))
    }

    private val decoder = SyntaxDecoder(locate) { code, splices, loc -> QuoteBuilder(splices, loc).build(code) }

    private fun roundTrip(d: ParsedDecl): ParsedDecl =
        decoder.decl(SyntaxEncoder.WITHOUT_FACTS.decl("m", d, HostValue.Opt(null)))

    // ── the normalizer ───────────────────────────────────────────────────

    private fun ParsedMetadata?.at(owner: Loc) = this?.copy(loc = owner)

    private fun normalizeDef(def: ParsedTypeDef): ParsedTypeDef = when (def) {
        is ParsedRecordDef -> def.copy(fields = def.fields.map { it.copy(metadata = it.metadata.at(it.loc)) })
        is ParsedVariantDef -> def.copy(constructors = def.constructors.map { it.copy(metadata = it.metadata.at(it.loc)) })
        is ParsedNewtypeDef, is ParsedTypeAliasDef -> def
    }

    private fun normalize(d: ParsedDecl): ParsedDecl = when (d) {
        is ParsedLetDecl -> d.copy(metadata = d.metadata.at(d.loc))
        is ParsedTypeDecl -> d.copy(metadata = d.metadata.at(d.loc), definition = normalizeDef(d.definition))
        is ParsedAbilityDecl -> d.copy(metadata = d.metadata.at(d.loc))
        is ParsedImplAbilityDecl -> d.copy(metadata = d.metadata.at(d.loc))
        is ParsedEffectDecl -> d.copy(metadata = d.metadata.at(d.loc))
        is ParsedPerformerDecl -> d.copy(metadata = d.metadata.at(d.loc))
        is ParsedUseDecl -> d.copy(metadata = d.metadata.at(d.loc))
        is ParsedPubUseDecl -> d.copy(metadata = d.metadata.at(d.loc))
        is ParsedTopLevelExpr -> d.copy(metadata = d.metadata.at(d.loc))
    }

    /** Where two trees first differ, as text, for a readable failure. */
    private fun firstDifference(a: Any, b: Any): String {
        val x = a.toString()
        val y = b.toString()
        val i = x.indices.firstOrNull { it >= y.length || x[it] != y[it] } ?: x.length
        val from = maxOf(0, i - 200)
        return "\n    original: …${x.substring(from, minOf(x.length, i + 200))}…" +
            "\n    decoded:  …${y.substring(from, minOf(y.length, i + 200))}…"
    }

    private fun assertRoundTrips(d: ParsedDecl, where: String) {
        val decoded = roundTrip(d)
        val expected = normalize(d)
        val actual = normalize(decoded)
        if (expected != actual) throw AssertionError("$where does not round-trip:${firstDifference(expected, actual)}")
    }

    private fun parse(source: String) = MezzeParser().parseParsedModule(source, "t.mz")

    // ── the corpus ───────────────────────────────────────────────────────

    private fun repoRoot(): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "stdlib/std").isDirectory }
        ?: error("no stdlib/std above ${System.getProperty("user.dir")}")

    @Test
    fun `every declaration in stdlib and examples round-trips`() {
        val root = repoRoot()
        val files = listOf("stdlib", "examples").map { File(root, it) }.filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "mz" && "build" !in it.relativeTo(root).path.split('/') }.toList() }
            .sortedBy { it.path }
        assertTrue(files.size > 50, "expected the stdlib's .mz files, found ${files.size}")

        var decls = 0
        val unparsed = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for (file in files) {
            val rel = file.relativeTo(root).path
            val module = try {
                MezzeParser().parseParsedModule(file.readText(), rel)
            } catch (e: Exception) {
                unparsed += rel
                continue
            }
            for (d in module.decls) {
                decls++
                try {
                    assertRoundTrips(d, "${d.loc}")
                } catch (e: AssertionError) {
                    failures += e.message ?: "$rel: ${d.loc}"
                } catch (e: SyntaxDecoder.Malformed) {
                    failures += "${d.loc}: decoder rejected the encoder's output: ${e.message}"
                }
            }
        }
        println("round-trip: ${files.size} files, ${unparsed.size} not parsed, $decls declarations, ${failures.size} failures")
        if (unparsed.isNotEmpty()) println("not parsed: $unparsed")
        assertTrue(decls > 1000, "expected the corpus's declarations, found $decls")
        if (failures.isNotEmpty()) {
            throw AssertionError("${failures.size} of $decls declarations do not round-trip:\n" + failures.take(15).joinToString("\n\n"))
        }
    }

    // ── focused ──────────────────────────────────────────────────────────

    @Test
    fun `comments everywhere the encoder keeps them round-trip`() {
        val module = parse(
            """
            let f = { x } -> do
              # above the let
              let a = {
                # above a field
                p = 1, # after a field
                ...x, # after a spread
              } # after the let
              let b = match x is
                # above an arm
                Some { val } -> val # after an arm
                None -> 0
              let c = x
                |> g { v = @ } # after a stage
                |> h { v = @ }
              # above the result
              a
            """.trimIndent(),
        )
        module.decls.forEach { assertRoundTrips(it, "${it.loc}") }
    }

    @Test
    fun `trailing comments on entries, statements and arms stay trailing`() {
        // The parser attaches a trailing comment only to a declaration today; the
        // tree holds one on these too, and the encoding must keep it apart from
        // the leading ones.
        val d = parse("let f = { x } -> do\n  let r = { p = 1 }\n  match x is\n    _ -> r").decls.single() as ParsedLetDecl
        fun note(t: String) = ParsedComment(t, false, Loc("t.mz", 9, 1, 9, t.length))
        val lambda = d.value as ParsedLambda
        val block = lambda.body as ParsedDo
        val let = block.statements.single() as ParsedStatement.Let
        val record = let.value as ParsedRecordLit
        val match = block.result as ParsedMatch
        val annotated = d.copy(value = lambda.copy(body = block.copy(
            statements = listOf(let.copy(
                value = record.copy(entries = record.entries.map {
                    (it as ParsedRecordEntry.Field).copy(leadingComments = listOf(note("# above")), trailingComment = note("# entry"))
                }),
                trailingComment = note("# let"),
            )),
            result = match.copy(cases = match.cases.map { it.copy(trailingComment = note("# arm")) }),
        )))
        assertEquals(annotated, roundTrip(annotated))
    }

    @Test
    fun `the names inside an effect call, an impl and a performer keep their own spans`() {
        val module = parse(
            """
            let a = perform Log::info { msg = "hello" }
            impl std::Eq for Thing where { eq = { it, r } -> True }
            impl Log for Quiet where { info = { msg } -> {} }
            impl Thing where { size = { it } -> 0 }
            """.trimIndent(),
        )
        assertEquals(4, module.decls.size)
        module.decls.forEach { assertEquals(it, roundTrip(it)) }
    }

    @Test
    fun `metadata round-trips through its JSON text`() {
        val module = parse(
            """
            #{deprecated: true, since: "1.0", n: 3, ratio: 1.5, tags: ["a", "b"], config: {timeout: 5000, nested: [1, 2.0]}}
            let x = 42
            """.trimIndent(),
        )
        val d = module.decls.single()
        assertRoundTrips(d, "metadata")
        assertEquals(d.metadata!!.fields, roundTrip(d).metadata!!.fields)
    }

    @Test
    fun `a quote round-trips, its value rebuilt`() {
        val d = parse("let q = `(a + ~b)").decls.single()
        val quote = (d as ParsedLetDecl).value as ParsedQuote
        assertTrue(quote.splices.isEmpty())
        assertEquals(d, roundTrip(d))
    }

    @Test
    fun `a quote with a spliced expression round-trips`() {
        val d = parse("let q = `(a + ~(f { x = 1 }))").decls.single()
        assertEquals(1, ((d as ParsedLetDecl).value as ParsedQuote).splices.size)
        assertEquals(d, roundTrip(d))
    }

    @Test
    fun `a quote of declarations round-trips`() {
        val d = parse("let q = `(let y = ~v)").decls.single()
        assertTrue(((d as ParsedLetDecl).value as ParsedQuote).template is ParsedCode.Decls)
        assertEquals(d, roundTrip(d))
    }

    @Test
    fun `without a way to build a quote's value, a quote is malformed`() {
        val d = parse("let q = `(a + ~b)").decls.single()
        val e = assertThrows<SyntaxDecoder.Malformed> {
            SyntaxDecoder(locate).decl(SyntaxEncoder.WITHOUT_FACTS.decl("m", d, HostValue.Opt(null)))
        }
        assertTrue("may not produce a quote" in e.message!!, e.message)
    }

    @Test
    fun `a macro call round-trips, with and without an expansion`() {
        val call = parse("let m = a::b::mac!(any text, at all)").decls.single()
        assertEquals(call, roundTrip(call))
        val let = call as ParsedLetDecl
        val expanded = let.copy(value = (let.value as ParsedMacroCall).copy(expansion = ParsedCode.Expr(ParsedIntLit(1, let.loc))))
        assertEquals(expanded, roundTrip(expanded))
    }

    // ── malformed input ──────────────────────────────────────────────────

    private val span = HostValue.record(
        "file" to HostValue.Opt(null),
        "start_line" to HostValue.IntV(1), "start_col" to HostValue.IntV(1),
        "end_line" to HostValue.IntV(1), "end_col" to HostValue.IntV(2),
    )

    private fun call(arg: HostValue) = HostValue.Variant("CallExpr", HostValue.record(
        "callee" to HostValue.Variant("NameExpr", HostValue.record("name" to HostValue.Str("f"), "module" to HostValue.Opt(null), "span" to span)),
        "arg" to arg,
        "span" to span,
    ))

    @Test
    fun `an unknown constructor is malformed, naming it and where it was`() {
        val e = assertThrows<SyntaxDecoder.Malformed> {
            decoder.expr(call(HostValue.Variant("BogusExpr", HostValue.record("span" to span))))
        }
        assertTrue("BogusExpr" in e.message!!, e.message)
        assertTrue("CallExpr" in e.message!! && "arg" in e.message!!, e.message)
    }

    @Test
    fun `a missing field is malformed, naming the constructor and the field`() {
        val e = assertThrows<SyntaxDecoder.Malformed> {
            decoder.expr(call(HostValue.Variant("IntExpr", HostValue.record("span" to span))))
        }
        assertTrue("IntExpr" in e.message!! && "`value`" in e.message!!, e.message)
    }

    @Test
    fun `a field of the wrong type is malformed`() {
        val e = assertThrows<SyntaxDecoder.Malformed> {
            decoder.expr(HostValue.Variant("IntExpr", HostValue.record("value" to HostValue.Str("1"), "span" to span)))
        }
        assertTrue("IntExpr" in e.message!! && "value" in e.message!! && "Int" in e.message!!, e.message)
    }

    @Test
    fun `an unknown operator is malformed`() {
        val one = HostValue.Variant("IntExpr", HostValue.record("value" to HostValue.IntV(1), "span" to span))
        val e = assertThrows<SyntaxDecoder.Malformed> {
            decoder.expr(HostValue.Variant("BinaryExpr", HostValue.record(
                "op" to HostValue.Str("<=>"), "left" to one, "right" to one, "span" to span,
            )))
        }
        assertTrue("<=>" in e.message!!, e.message)
    }

    @Test
    fun `a character is one Char`() {
        val e = assertThrows<SyntaxDecoder.Malformed> {
            decoder.expr(HostValue.Variant("CharExpr", HostValue.record("value" to HostValue.Str("ab"), "span" to span)))
        }
        assertTrue("CharExpr" in e.message!!, e.message)
    }
}
