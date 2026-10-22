package com.mezze.language.parser.internal

import com.mezze.language.comptime.HostValue
import com.mezze.language.comptime.SyntaxEncoder
import com.mezze.language.parsedast.*

/**
 * The code that builds a quote's value.
 *
 * A quote's value is the quoted code as `std::syntax` data, and the encoder that
 * answers codebase questions already turns code into exactly that data. So the
 * quote is encoded with it, and the data is then written back out as the
 * expressions that construct it — with each splice put where its placeholder
 * was. The shapes therefore come from one place: a quote cannot build a node
 * the codebase would describe differently, and the decoding of a macro's output
 * reads both the same way.
 *
 * A splice is a name spelled with a leading `~` (see [ParsedQuote]). Where it
 * lands decides what it must be:
 * - in place of a whole expression, pattern or type, it IS that node: an
 *   `Expr`, a `Pattern`, a `TypeExpr`;
 * - in place of a name that is not a node — a field, a member, a constructor,
 *   a parameter — it is the `Str`;
 * - as `...~xs` in a sequence or a record, it is that list's elements: a
 *   `[SeqElement]` or an `[Entry]`.
 * The type checker holds each splice to that type, at the splice.
 *
 * Text is never a splice: a string literal or a comment containing `~x` is just
 * text, so the walk tracks whether it is inside one.
 */
internal class QuoteBuilder(
    private val splices: List<ParsedExpr>,
    private val loc: Loc,
) {
    /**
     * The value, held to its `std::syntax` type: `do let ~quote: T = <value>; ~quote`.
     *
     * The type is not optional. Without it every constructor in the quote is an
     * anonymous one, inferred from this quote alone, and two quotes in one module
     * disagree about what `IntExpr` holds (`facts = None` says nothing about the
     * option's element). The type is named as [QuoteTypes] brings it into scope.
     */
    fun build(code: ParsedCode): ParsedExpr {
        val encoder = SyntaxEncoder.WITHOUT_FACTS
        val (value, type) = when (code) {
            is ParsedCode.Expr -> encoder.expr(code.expr) to ParsedNamedType(QuoteTypes.EXPR, loc)
            is ParsedCode.Decls -> HostValue.Seq(code.decls.map { encoder.decl("", it, HostValue.Opt(null)) }) to
                ParsedSeqType(ParsedNamedType(QuoteTypes.SYNTAX_DECL, loc), loc)
        }
        return ParsedDo(
            listOf(ParsedStatement.Let(ParsedVariablePattern(QUOTED, loc), type, emit(value, text = false), loc)),
            ParsedIdentifier(QUOTED, null, loc),
            loc,
        )
    }


    private fun emit(v: HostValue, text: Boolean): ParsedExpr = when (v) {
        is HostValue.Str -> (if (text) null else spliced(v.value, loc)) ?: str(v.value)
        is HostValue.IntV -> ParsedIntLit(v.value, loc)
        is HostValue.FloatV -> ParsedFloatLit(v.value, loc)
        is HostValue.Bool -> ParsedVariantConstruct(if (v.value) "True" else "False", null, loc)
        is HostValue.Opt -> v.value?.let { some(emit(it, text)) } ?: ParsedVariantConstruct("None", null, loc)
        is HostValue.Seq -> ParsedSeqLit(v.items.map { item ->
            listSplice(item)?.let { ParsedSeqElement.Spread(it, loc) } ?: ParsedSeqElement.Regular(emit(item, text))
        }, loc)
        is HostValue.Record -> record(v, text)
        is HostValue.Variant -> wholeNode(v) ?: ParsedVariantConstruct(
            v.tag,
            v.payload?.let { record(it, text || v.tag in TEXT_VARIANTS) },
            loc,
        )
    }

    private fun record(r: HostValue.Record, text: Boolean): ParsedExpr = ParsedRecordLit(
        r.fields.map { (name, value) ->
            ParsedRecordEntry.Field(name, emit(value, text || name in TEXT_FIELDS), loc)
        },
        loc,
    )

    /** `~x` standing for a whole expression, pattern or type: the node is the splice. */
    private fun wholeNode(v: HostValue.Variant): ParsedExpr? {
        if (v.tag !in NODE_SPLICES) return null
        val fields = v.payload?.fields?.toMap() ?: return null
        if (v.tag == "NameExpr" && (fields["module"] as? HostValue.Opt)?.value != null) return null
        val name = (fields["name"] as? HostValue.Str)?.value ?: return null
        return spliced(name, spanLoc(fields["span"]) ?: loc)
    }

    /** `...~xs` in a sequence or a record: the elements are the splice. */
    private fun listSplice(item: HostValue): ParsedExpr? {
        val v = item as? HostValue.Variant ?: return null
        if (v.tag != "SpreadElement" && v.tag != "SpreadEntry") return null
        val inner = v.payload?.fields?.toMap()?.get("expr") as? HostValue.Variant ?: return null
        return if (inner.tag == "NameExpr") wholeNode(inner) else null
    }

    /** The expression a placeholder name stands for, or null for an ordinary name. */
    private fun spliced(name: String, at: Loc): ParsedExpr? {
        if (!name.startsWith(SPLICE)) return null
        val rest = name.substring(SPLICE.length)
        if (rest.startsWith("(") && rest.endsWith(")")) {
            return splices[rest.substring(1, rest.length - 1).toInt()]
        }
        return ParsedIdentifier(rest, null, at)
    }

    private fun spanLoc(span: HostValue?): Loc? {
        val f = (span as? HostValue.Record)?.fields?.toMap() ?: return null
        fun int(k: String): Int? = (f[k] as? HostValue.IntV)?.value?.toInt()
        val file = ((f["file"] as? HostValue.Opt)?.value as? HostValue.Str)?.value ?: loc.file
        return Loc(file, int("start_line") ?: return null, int("start_col") ?: return null,
            int("end_line") ?: return null, int("end_col") ?: return null)
    }

    private fun str(s: String) = ParsedStringLit(listOf(ParsedStringPart.Literal(s)), loc)

    private fun some(e: ParsedExpr) =
        ParsedVariantConstruct("Some", ParsedRecordLit(listOf(ParsedRecordEntry.Field("val", e, loc)), loc), loc)

    companion object {
        /** How a splice placeholder name begins. */
        const val SPLICE = "~"

        /** The binding a quote's value is held to its type through; no source name can spell it. */
        private const val QUOTED = "~quote"

        /** Nodes a lone `~x` replaces whole. */
        private val NODE_SPLICES = setOf("NameExpr", "VarPat", "NamedType")

        /** Fields whose strings are text, never names. */
        private val TEXT_FIELDS = setOf("text", "doc", "metadata", "input")

        /** Constructors whose payload is text: a string pattern's literal. */
        private val TEXT_VARIANTS = setOf("StrLiteral")
    }
}
